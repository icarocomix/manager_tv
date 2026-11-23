package com.icarocomix.icaromanagertv.mobile.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.icarocomix.icaromanagertv.common.files.FileNames
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import com.icarocomix.icaromanagertv.mobile.R
import com.icarocomix.icaromanagertv.mobile.discovery.TvEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Trasferimento richiesto dall'interfaccia. */
sealed class TransferJob {
    abstract val tv: TvEndpoint

    /** File del telefono ([uris]) verso la cartella dell'elenco [seq] della TV; [move] elimina gli originali. */
    data class Upload(override val tv: TvEndpoint, val seq: Int, val uris: List<Uri>, val move: Boolean) : TransferJob()

    /** File [index] dell'elenco [seq] della TV verso il documento [dest] scelto sul telefono. */
    data class Download(override val tv: TvEndpoint, val seq: Int, val index: Int, val name: String, val size: Long, val dest: Uri) : TransferJob()
}

/** Avanzamento mostrato dall'interfaccia: [permille] -1 se non stimabile, [queued] lavori in attesa. */
data class TransferState(val text: String, val permille: Int, val queued: Int)

/**
 * Punto d'incontro tra interfaccia e [TransferService] nello stesso processo: coda dei lavori,
 * stato di avanzamento ed esiti. La coda vive in memoria: un trasferimento non sopravvive alla
 * chiusura forzata del processo, ed è voluto (riprendere a metà un file da GB senza che l'utente
 * lo sappia sarebbe peggio che segnalarlo come non riuscito).
 */
object TransferCenter {

    private val queue = ConcurrentLinkedQueue<TransferJob>()
    internal val cancelRequested = AtomicBoolean(false)

    @Volatile
    internal var activeCall: Call? = null

    private val _state = MutableStateFlow<TransferState?>(null)
    val state: StateFlow<TransferState?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Esiti da mostrare all'utente (toast). */
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Accoda un trasferimento e avvia il servizio in primo piano che li esegue in ordine. */
    fun enqueue(context: Context, job: TransferJob) {
        queue.add(job)
        val intent = Intent(context, TransferService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Annulla il trasferimento in corso e svuota la coda. */
    fun cancelAll() {
        queue.clear()
        cancelRequested.set(true)
        activeCall?.cancel()
    }

    internal fun poll(): TransferJob? = queue.poll()
    internal fun queued(): Int = queue.size
    internal fun publish(state: TransferState?) {
        _state.value = state
    }

    internal fun emit(text: String) {
        _events.tryEmit(text)
    }
}

/**
 * Esegue i trasferimenti con la TV in un servizio in primo piano (notifica con avanzamento e
 * pulsante Annulla): l'invio di un video da qualche GB continua anche se l'utente esce dall'app
 * o spegne lo schermo. Un lavoro alla volta, nell'ordine di arrivo: la TV accetta comunque una
 * sola scrittura per volta.
 *
 * Il corpo delle richieste viaggia in streaming dal content provider del telefono al socket (e
 * viceversa per lo scaricamento): nessuna copia temporanea, memoria costante.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private lateinit var notifications: NotificationManager
    private var lastNotifyAt = 0L

    // Timeout ampi: la TV può rallentare a lungo mentre la chiavetta svuota la sua cache.
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.transfer_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferCenter.cancelAll()
            // Notifica rimasta da un servizio già concluso: nessun lavoro da fermare, chiudo subito.
            if (worker?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        // Da Android 8 startForeground va chiamato subito dopo startForegroundService.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(getString(R.string.transfer_preparing), -1), type)
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        TransferCenter.publish(null)
        super.onDestroy()
    }

    private fun runQueue() {
        while (true) {
            val job = TransferCenter.poll() ?: break
            TransferCenter.cancelRequested.set(false)
            when (job) {
                is TransferJob.Upload -> runUpload(job)
                is TransferJob.Download -> runDownload(job)
            }
        }
        TransferCenter.publish(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ caricamento

    private fun runUpload(job: TransferJob.Upload) {
        var sent = 0
        for (uri in job.uris) {
            if (TransferCenter.cancelRequested.get()) break
            val meta = queryMeta(uri)
            val name = FileNames.validOrNull(meta.name ?: "") ?: "file_${System.currentTimeMillis()}"
            try {
                uploadOne(job, uri, name, meta.size)
                sent++
                if (job.move) deleteSource(uri, name)
            } catch (e: UnsupportedTvException) {
                TransferCenter.emit(getString(R.string.transfer_unsupported))
                return
            } catch (e: IOException) {
                if (TransferCenter.cancelRequested.get()) {
                    TransferCenter.emit(getString(R.string.transfer_cancelled))
                    return
                }
                Log.w(TAG, "Invio di «$name» non riuscito", e)
                TransferCenter.emit(getString(R.string.transfer_failed, name, e.message ?: e.javaClass.simpleName))
                // Il file successivo può andare a buon fine (es. questo era illeggibile): proseguo.
            } finally {
                releasePermission(uri)
            }
        }
        if (TransferCenter.cancelRequested.get()) {
            TransferCenter.emit(getString(R.string.transfer_cancelled))
        } else if (sent > 0) {
            TransferCenter.emit(getString(if (job.move) R.string.transfer_upload_moved else R.string.transfer_upload_done, sent))
        }
    }

    private fun uploadOne(job: TransferJob.Upload, uri: Uri, name: String, knownSize: Long?) {
        // Senza Content-Length la TV rifiuta il caricamento: se il provider non conosce la
        // dimensione la misuro leggendo il file una volta (caso raro).
        val size = knownSize?.takeIf { it >= 0 } ?: measure(uri)
        val text = getString(R.string.transfer_uploading, name)
        progress(text, 0)
        val mime = (contentResolver.getType(uri) ?: "application/octet-stream").toMediaTypeOrNull()
        val body = object : RequestBody() {
            override fun contentType() = mime
            override fun contentLength() = size

            override fun writeTo(sink: BufferedSink) {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("file non leggibile")
                input.use {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var done = 0L
                    while (true) {
                        if (TransferCenter.cancelRequested.get()) throw IOException("annullato")
                        val read = it.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        progress(text, if (size > 0) (done * 1000 / size).toInt() else -1)
                    }
                }
            }
        }
        val url = baseUrl(job.tv, Protocol.UPLOAD_PATH)
            .addQueryParameter(Protocol.SEQ_PARAM, job.seq.toString())
            .addQueryParameter(Protocol.NAME_PARAM, name)
            .build()
        val call = http.newCall(Request.Builder().url(url).post(body).build())
        TransferCenter.activeCall = call
        try {
            call.execute().use { response ->
                if (response.code == 404) throw UnsupportedTvException()
                val text2 = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(text2.take(300).ifBlank { "la TV ha risposto ${response.code}" })
                val finalName = runCatching { JSONObject(text2).optString("name") }.getOrNull()
                Log.i(TAG, "Inviato alla TV: «$name» come «$finalName» ($size byte)")
            }
        } finally {
            TransferCenter.activeCall = null
        }
    }

    /** Elimina l'originale dopo uno spostamento riuscito; un rifiuto del provider non è un errore del trasferimento. */
    private fun deleteSource(uri: Uri, name: String) {
        val deleted = runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
            .onFailure { Log.w(TAG, "Originale non eliminato: $uri", it) }
            .getOrDefault(false)
        if (!deleted) TransferCenter.emit(getString(R.string.transfer_move_not_deleted, name))
    }

    private fun measure(uri: Uri): Long {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("file non leggibile")
        return input.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }

    private data class Meta(val name: String?, val size: Long?)

    private fun queryMeta(uri: Uri): Meta = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            Meta(c.getString(0), if (c.isNull(1)) null else c.getLong(1))
        }
    }.getOrNull() ?: Meta(uri.lastPathSegment, null)

    private fun releasePermission(uri: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------ scaricamento

    private fun runDownload(job: TransferJob.Download) {
        val text = getString(R.string.transfer_downloading, job.name)
        progress(text, 0)
        val url = baseUrl(job.tv, Protocol.DOWNLOAD_PATH)
            .addQueryParameter(Protocol.SEQ_PARAM, job.seq.toString())
            .addQueryParameter(Protocol.INDEX_PARAM, job.index.toString())
            .build()
        val call = http.newCall(Request.Builder().url(url).build())
        TransferCenter.activeCall = call
        var completed = false
        try {
            call.execute().use { response ->
                if (response.code == 404) throw UnsupportedTvException()
                if (!response.isSuccessful) {
                    throw IOException(response.body?.string()?.take(300)?.ifBlank { null } ?: "la TV ha risposto ${response.code}")
                }
                val body = response.body ?: throw IOException("risposta vuota dalla TV")
                val total = body.contentLength().takeIf { it > 0 } ?: job.size
                val output = contentResolver.openOutputStream(job.dest, "w") ?: throw IOException("destinazione non scrivibile")
                output.use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var done = 0L
                        while (true) {
                            if (TransferCenter.cancelRequested.get()) throw IOException("annullato")
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            progress(text, if (total > 0) (done * 1000 / total).toInt() else -1)
                        }
                    }
                }
            }
            completed = true
            Log.i(TAG, "Ricevuto dalla TV: «${job.name}»")
            TransferCenter.emit(getString(R.string.transfer_download_done, job.name))
        } catch (e: UnsupportedTvException) {
            TransferCenter.emit(getString(R.string.transfer_unsupported))
        } catch (e: IOException) {
            if (TransferCenter.cancelRequested.get()) {
                TransferCenter.emit(getString(R.string.transfer_cancelled))
            } else {
                Log.w(TAG, "Ricezione di «${job.name}» non riuscita", e)
                TransferCenter.emit(getString(R.string.transfer_failed, job.name, e.message ?: e.javaClass.simpleName))
            }
        } finally {
            TransferCenter.activeCall = null
            // Niente file a metà sul telefono: il documento creato dal selettore viene eliminato.
            if (!completed) runCatching { DocumentsContract.deleteDocument(contentResolver, job.dest) }
        }
    }

    // ------------------------------------------------------------------ comuni

    private fun baseUrl(tv: TvEndpoint, path: String): HttpUrl.Builder = HttpUrl.Builder()
        .scheme("http")
        .host(tv.host)
        .port(tv.port)
        .encodedPath(path)
        .addQueryParameter(Protocol.LOGS_ID_PARAM, tv.id)

    /** Pubblica l'avanzamento all'interfaccia e, diradato, alla notifica. */
    private fun progress(text: String, permille: Int) {
        val queued = TransferCenter.queued()
        val shown = if (queued > 0) getString(R.string.transfer_queue, text, queued) else text
        TransferCenter.publish(TransferState(shown, permille, queued))
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        notifications.notify(NOTIFICATION_ID, buildNotification(shown, permille))
    }

    private fun buildNotification(text: String, permille: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_upload)
        .setContentTitle(getString(R.string.transfer_notification_title))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(1000, permille.coerceAtLeast(0), permille < 0)
        .addAction(
            0,
            getString(R.string.transfer_cancel),
            PendingIntent.getService(
                this,
                0,
                Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    /** La TV non ha gli endpoint di trasferimento (versione precedente alla v1.9). */
    private class UnsupportedTvException : IOException("TV senza trasferimenti")

    private companion object {
        const val TAG = "TransferService"
        const val CHANNEL = "transfers"
        const val NOTIFICATION_ID = 4711
        const val ACTION_CANCEL = "com.icarocomix.icaromanagertv.mobile.CANCEL_TRANSFER"
        const val BUFFER_SIZE = 128 * 1024
        const val NOTIFY_INTERVAL_MS = 500L
    }
}
