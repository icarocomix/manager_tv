package com.icarocomix.icaromanagertv.tv.diagnostics

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.icarocomix.icaromanagertv.common.log.AppLogs
import com.icarocomix.icaromanagertv.common.util.TimeFormat
import com.icarocomix.icaromanagertv.tv.TvApp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Esportazione dei log della TV: stesso pacchetto ZIP ([AppLogs]) verso destinazioni diverse.
 * Tutti i metodi fanno I/O su disco e lanciano logcat: vanno chiamati fuori dal main thread.
 *
 * Il pacchetto aggiunge alle informazioni del dispositivo lo stato dell'app al momento
 * dell'esportazione (server, telefoni collegati, riproduzione): spesso basta per capire in
 * che situazione si trovava la TV quando si è presentato il problema.
 */
class LogExporter(private val app: TvApp) {

    /** Scrive il pacchetto completo su [out] e lo chiude. */
    fun writeZip(out: OutputStream) = AppLogs.writeZip(app, out, appState())

    /**
     * Salva in `Download/ManagerTV`. Da Android 10 passo da MediaStore (nessun permesso);
     * prima scrivo direttamente nella cartella pubblica (permesso WRITE_EXTERNAL_STORAGE).
     * @return percorso leggibile da mostrare all'utente
     */
    fun saveToDownloads(): String {
        val name = AppLogs.suggestedFileName(SOURCE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_ZIP)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                // In scrittura il file resta nascosto alle altre app finché non è completo.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore non ha creato il file $name")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Scrittura di $name non consentita")
                writeZip(out)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                // Non lascio un file vuoto e invisibile in Download.
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            Log.i(TAG, "Log esportati in Download/$FOLDER/$name")
            return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        return saveToFolder(dir).absolutePath
    }

    /** Salva nella cartella [dir] (es. la cartella del disco USB aperta nel file manager). */
    fun saveToFolder(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cartella non creabile: ${dir.absolutePath}")
        val target = File(dir, AppLogs.suggestedFileName(SOURCE))
        try {
            writeZip(FileOutputStream(target))
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        Log.i(TAG, "Log esportati in ${target.absolutePath}")
        return target
    }

    /**
     * Prepara il pacchetto nella cache e restituisce l'Intent di condivisione (email, Drive,
     * Bluetooth… a seconda delle app presenti sulla TV). Tengo solo l'ultimo pacchetto.
     */
    fun shareIntent(): Intent {
        val dir = File(app.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, AppLogs.suggestedFileName(SOURCE))
        writeZip(FileOutputStream(file))
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME_ZIP)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    /**
     * Pacchetto temporaneo per l'invio in rete al telefono: il chiamante lo cancella dopo
     * l'invio. Lo costruisco su file e non in memoria: le TV economiche hanno heap ridotto.
     */
    fun buildTemporaryZip(): File {
        val dir = File(app.cacheDir, HTTP_DIR).apply { mkdirs() }
        val file = File.createTempFile("logs-", ".zip", dir)
        try {
            writeZip(FileOutputStream(file))
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    /** Cartella del file manager in cui posso scrivere, o null (radice, SAF, sola lettura). */
    fun writableCurrentFolder(): File? = app.controller.currentFolder?.takeIf { it.isDirectory && it.canWrite() }

    private fun appState(): Map<String, String> {
        val server = app.serverInfo.value
        val playback = app.playback.state.value
        val state = app.controller.state.value
        return linkedMapOf(
            "Server" to if (server.running) "attivo su ${server.address ?: "?"}:${server.port}, telefoni collegati ${server.clients}" else "fermo",
            "In riproduzione" to when {
                playback.active -> buildString {
                    append("«${playback.title.orEmpty()}» ${TimeFormat.format(playback.positionMs)}")
                    if (playback.durationMs > 0) append(" / ${TimeFormat.format(playback.durationMs)}")
                    append(if (playback.playing) ", in riproduzione" else ", in pausa")
                    if (playback.buffering) append(", buffering")
                    append(", file ${playback.index + 1} di ${playback.count}, vista ${playback.view.wire}, autoplay ${playback.autoplay}")
                }
                state.nowPlaying != null -> "player esterno: «${state.nowPlaying}»"
                else -> "nulla"
            },
            "Cartella corrente" to (app.controller.currentFolder?.absolutePath ?: "radice / albero SAF"),
        )
    }

    companion object {
        private const val TAG = "LogExporter"
        private const val SOURCE = "tv"
        private const val FOLDER = "ManagerTV"
        private const val SHARE_DIR = "log-share"
        private const val HTTP_DIR = "log-http"
        const val MIME_ZIP = "application/zip"
    }
}
