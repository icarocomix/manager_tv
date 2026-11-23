package com.icarocomix.icaromanagertv.tv.server

import android.content.Context
import android.util.Log
import com.icarocomix.icaromanagertv.common.files.FileNames
import com.icarocomix.icaromanagertv.common.net.LocalNetwork
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import com.icarocomix.icaromanagertv.common.protocol.ProtocolCodec
import com.icarocomix.icaromanagertv.common.protocol.ServerMessage
import com.icarocomix.icaromanagertv.tv.R
import com.icarocomix.icaromanagertv.tv.controller.BrowserController
import com.icarocomix.icaromanagertv.tv.diagnostics.LogExporter
import com.icarocomix.icaromanagertv.tv.playback.PlaybackController
import com.icarocomix.icaromanagertv.tv.storage.FileOperationException
import com.icarocomix.icaromanagertv.tv.storage.FileOperations
import com.icarocomix.icaromanagertv.tv.storage.MediaTypes
import com.icarocomix.icaromanagertv.tv.storage.PendingFile
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Server HTTP + WebSocket della TV. Ogni telefono apre un WebSocket su [Protocol.WS_PATH]; il
 * server gli invia subito saluto, elenco e stato correnti, poi ogni variazione di [controller].
 * I comandi ricevuti passano al controller senza elaborazione: tutta la logica sta lì.
 *
 * Gli invii a un client passano da un suo executor a thread singolo: l'ordine dei messaggi è
 * garantito e un telefono lento (Wi-Fi debole) non rallenta né gli altri né il controller.
 */
class RemoteServer private constructor(
    port: Int,
    private val context: Context,
    private val controller: BrowserController,
    private val files: FileOperations,
    private val playback: PlaybackController,
    private val logExporter: LogExporter,
    private val serverName: String,
    private val serverId: String,
    private val appVersion: String,
) : NanoWSD(port) {

    private val clients = CopyOnWriteArraySet<ClientSocket>()
    private val _clientCount = MutableStateFlow(0)

    /** Numero di telefoni collegati, mostrato sulla TV. */
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    // Ultimi messaggi già serializzati: un nuovo client li riceve senza ricodificare l'elenco.
    @Volatile private var lastListingJson: String? = null
    @Volatile private var lastStateJson: String? = null
    @Volatile private var lastPlaybackJson: String? = null
    @Volatile private var lastProgressJson: String? = null
    @Volatile private var lastStatusJson: String? = null
    private var broadcastJob: Job? = null

    private fun startBroadcasting(scope: CoroutineScope) {
        broadcastJob = scope.launch {
            launch {
                // Il telefono riceve la propria sessione di navigazione: con la sincronizzazione
                // attiva coincide con quella della TV (v1.9).
                controller.remoteListing.collect { listing ->
                    val json = ProtocolCodec.encode(ServerMessage.ListingMsg(listing))
                    lastListingJson = json
                    broadcast(json)
                }
            }
            launch {
                controller.remoteState.collect { state ->
                    val json = ProtocolCodec.encode(ServerMessage.StateMsg(state))
                    lastStateJson = json
                    broadcast(json)
                }
            }
            launch {
                // Durante la riproduzione arriva un aggiornamento al secondo: messaggio piccolo,
                // separato da elenco e stato del file manager.
                playback.state.collect { playbackState ->
                    val json = ProtocolCodec.encode(ServerMessage.PlaybackMsg(playbackState))
                    lastPlaybackJson = json
                    broadcast(json)
                }
            }
            launch {
                // Parte vista dei file elencati: riferita alla sequenza dell'elenco, piccola.
                controller.remoteProgress.collect { progress ->
                    val json = ProtocolCodec.encode(ServerMessage.ProgressMsg(progress))
                    lastProgressJson = json
                    broadcast(json)
                }
            }
            launch {
                // Sincronizzazione, appunti e operazione in corso: comuni a TV e telefoni.
                controller.status.collect { status ->
                    val json = ProtocolCodec.encode(ServerMessage.StatusMsg(status))
                    lastStatusJson = json
                    broadcast(json)
                }
            }
            launch {
                controller.toasts.collect { text -> broadcast(ProtocolCodec.encode(ServerMessage.Toast(text))) }
            }
        }
    }

    private fun broadcast(json: String) {
        clients.forEach { it.enqueue(json) }
    }

    fun shutdown() {
        broadcastJob?.cancel()
        clients.forEach { it.closeQuietly() }
        clients.clear()
        _clientCount.value = 0
        stop()
    }

    // ------------------------------------------------------------------ HTTP

    override fun serve(session: IHTTPSession): Response {
        // Servizio pensato per la rete di casa: rifiuto qualunque richiesta da indirizzi non
        // locali (es. port forwarding configurato per errore sul router).
        val remote = runCatching { InetAddress.getByName(session.remoteIpAddress) }.getOrNull()
        if (!LocalNetwork.isLocalAddress(remote)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Solo rete locale")
        }
        if (isWebsocketRequested(session) && session.uri != Protocol.WS_PATH) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        return super.serve(session)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        // Il caricamento dal telefono è l'unica richiesta con un corpo (v1.9).
        if (session.uri == Protocol.UPLOAD_PATH) return serveUpload(session)
        if (session.method != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Metodo non consentito")
        }
        return when (session.uri) {
            Protocol.INFO_PATH, "/" -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject()
                    .put("app", Protocol.MAGIC)
                    .put("protocol", Protocol.VERSION)
                    .put("name", serverName)
                    .put("id", serverId)
                    .put("version", appVersion)
                    .put("clients", clients.size)
                    .toString(),
            )
            Protocol.LOGS_PATH -> serveLogs(session)
            Protocol.DOWNLOAD_PATH -> serveDownload(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    /**
     * Pacchetto ZIP dei log per il telefono. Oltre al filtro di rete locale chiedo l'id della
     * TV, che conosce solo un client già collegato al WebSocket: un dispositivo qualunque della
     * rete non scarica i log (che contengono nomi di file e cartelle) con una semplice richiesta.
     * NanoHTTPD gestisce ogni richiesta sul proprio thread: posso costruire lo ZIP qui.
     */
    private fun serveLogs(session: IHTTPSession): Response {
        val id = session.parameters[Protocol.LOGS_ID_PARAM]?.firstOrNull()
        if (id != serverId) {
            Log.w(TAG, "Richiesta dei log rifiutata da ${session.remoteIpAddress}: id mancante o errato")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Identificativo della TV non valido")
        }
        Log.i(TAG, "Invio dei log a ${session.remoteIpAddress}")
        val zip = try {
            logExporter.buildTemporaryZip()
        } catch (e: Exception) {
            // Anche errori non di I/O (permessi, logcat assente): rispondo invece di chiudere il socket.
            Log.e(TAG, "Pacchetto dei log non creato", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Log non disponibili: ${e.message}")
        }
        // Il file temporaneo si cancella quando NanoHTTPD chiude il flusso, a invio concluso o fallito.
        val stream = object : FileInputStream(zip) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    zip.delete()
                }
            }
        }
        return newFixedLengthResponse(Response.Status.OK, LogExporter.MIME_ZIP, stream, zip.length()).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${zip.name}\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    // ------------------------------------------------------------------ trasferimenti (v1.9)

    /** Stessa protezione dei log: solo un telefono collegato al WebSocket conosce l'id della TV. */
    private fun hasValidId(session: IHTTPSession): Boolean = session.parameters[Protocol.LOGS_ID_PARAM]?.firstOrNull() == serverId

    private fun param(session: IHTTPSession, name: String): String? = session.parameters[name]?.firstOrNull()

    /**
     * Risposta d'errore che chiude anche la connessione: se il corpo della richiesta non è stato
     * letto per intero, la connessione non è riutilizzabile (i byte rimasti verrebbero letti
     * come la richiesta successiva).
     */
    private fun failure(status: Response.Status, text: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, text).apply { closeConnection(true) }

    /**
     * Caricamento di un file dal telefono nella cartella dell'elenco indicato. Leggo il corpo in
     * streaming direttamente dal socket verso il disco (niente file temporanei di NanoHTTPD, che
     * raddoppierebbero spazio e tempo per un film da qualche GB); un caricamento incompleto o
     * fallito non lascia file a metà.
     */
    private fun serveUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST && session.method != Method.PUT) {
            return failure(Response.Status.METHOD_NOT_ALLOWED, "Metodo non consentito")
        }
        if (!hasValidId(session)) {
            Log.w(TAG, "Caricamento rifiutato da ${session.remoteIpAddress}: id mancante o errato")
            return failure(Response.Status.FORBIDDEN, "Identificativo della TV non valido")
        }
        val seq = param(session, Protocol.SEQ_PARAM)?.toIntOrNull()
            ?: return failure(Response.Status.BAD_REQUEST, "Parametro ${Protocol.SEQ_PARAM} mancante")
        val name = param(session, Protocol.NAME_PARAM)?.let(FileNames::validOrNull)
            ?: return failure(Response.Status.BAD_REQUEST, context.getString(R.string.upload_err_name))
        val length = session.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
            ?: return failure(Response.Status.LENGTH_REQUIRED, "Content-Length obbligatorio")
        val folder = controller.uploadFolder(seq)
            ?: return failure(Response.Status.CONFLICT, context.getString(R.string.upload_err_folder))
        if (!controller.beginTransfer(context.getString(R.string.fop_working_upload, name))) {
            return failure(Response.Status.CONFLICT, context.getString(R.string.fop_err_busy))
        }
        Log.i(TAG, "Caricamento di «$name» ($length byte) da ${session.remoteIpAddress} in ${folder.name}")
        var pending: PendingFile? = null
        try {
            val target = files.createUniqueFile(folder, name, MediaTypes.mimeFor(name))
            pending = target
            val input = session.inputStream
            val buffer = ByteArray(UPLOAD_BUFFER)
            var remaining = length
            var lastNotify = 0L
            val text = context.getString(R.string.fop_working_upload, target.name)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("caricamento interrotto dopo ${length - remaining} byte su $length")
                target.output.write(buffer, 0, read)
                remaining -= read
                val now = System.currentTimeMillis()
                if (now - lastNotify >= PROGRESS_INTERVAL_MS) {
                    lastNotify = now
                    controller.updateTransfer(text, if (length > 0) ((length - remaining) * 1000 / length).toInt() else -1)
                }
            }
            target.commit()
            Log.i(TAG, "Caricamento completato: «${target.name}»")
            controller.endTransfer(folder, target.name, context.getString(R.string.fop_done_upload, target.name))
            return newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject().put("name", target.name).toString())
        } catch (e: FileOperationException) {
            pending?.abort()
            Log.w(TAG, "Caricamento di «$name» non riuscito", e)
            if (e.needsFullAccess) controller.reportAccessNeeded()
            controller.endTransfer(null, null, e.message)
            return failure(Response.Status.INTERNAL_ERROR, e.message ?: "Scrittura non riuscita")
        } catch (e: Exception) {
            // Socket caduto (telefono uscito dal Wi-Fi, annullamento) o errore del disco.
            pending?.abort()
            Log.w(TAG, "Caricamento di «$name» interrotto", e)
            controller.endTransfer(null, null, context.getString(R.string.upload_err_interrupted, name))
            return failure(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Scaricamento sul telefono di un file dell'elenco mostrato al telefono. Sola lettura: nessuna corsia occupata. */
    private fun serveDownload(session: IHTTPSession): Response {
        if (!hasValidId(session)) {
            Log.w(TAG, "Scaricamento rifiutato da ${session.remoteIpAddress}: id mancante o errato")
            return failure(Response.Status.FORBIDDEN, "Identificativo della TV non valido")
        }
        val seq = param(session, Protocol.SEQ_PARAM)?.toIntOrNull()
        val index = param(session, Protocol.INDEX_PARAM)?.toIntOrNull()
        val node = if (seq != null && index != null) controller.downloadNode(seq, index) else null
        if (node == null) return failure(Response.Status.CONFLICT, context.getString(R.string.download_err_gone))
        val input = try {
            files.openRead(node)
        } catch (e: FileOperationException) {
            Log.w(TAG, "Scaricamento di «${node.name}» non possibile", e)
            return failure(Response.Status.INTERNAL_ERROR, e.message ?: "Lettura non riuscita")
        }
        Log.i(TAG, "Invio di «${node.name}» (${node.size} byte) a ${session.remoteIpAddress}")
        val mime = MediaTypes.mimeFor(node.name)
        val response = if (node.size >= 0) {
            newFixedLengthResponse(Response.Status.OK, mime, input, node.size)
        } else {
            newChunkedResponse(Response.Status.OK, mime, input)
        }
        return response.apply {
            // Il nome vero lo conosce già il telefono dall'elenco: qui basta un ripiego ASCII.
            addHeader("Content-Disposition", "attachment; filename=\"${node.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")}\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = ClientSocket(handshake)

    // ------------------------------------------------------------------ WebSocket

    private inner class ClientSocket(handshake: IHTTPSession) : WebSocket(handshake) {

        private val sender: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ws-send-${handshake.remoteIpAddress}").apply { isDaemon = true }
        }
        private val remote = handshake.remoteIpAddress

        override fun onOpen() {
            clients += this
            _clientCount.value = clients.size
            Log.i(TAG, "Client collegato: $remote (totale ${clients.size})")
            enqueue(ProtocolCodec.encode(ServerMessage.Hello(Protocol.VERSION, serverName, appVersion, serverId)))
            lastListingJson?.let(::enqueue)
            lastStateJson?.let(::enqueue)
            lastPlaybackJson?.let(::enqueue)
            lastProgressJson?.let(::enqueue)
            lastStatusJson?.let(::enqueue)
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            release()
            Log.i(TAG, "Client scollegato: $remote ($code ${reason.orEmpty()})")
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            when (val decoded = ProtocolCodec.decodeClient(text)) {
                null -> Log.w(TAG, "Messaggio non riconosciuto da $remote")
                is ClientMessage.Hello -> if (decoded.protocol != Protocol.VERSION) {
                    // Versioni incompatibili: spiego il motivo e chiudo, il telefono lo mostrerà.
                    enqueue(ProtocolCodec.encode(ServerMessage.Error("Versione del protocollo incompatibile: TV ${Protocol.VERSION}, telefono ${decoded.protocol}. Aggiorna entrambe le app.")))
                    sender.execute { closeQuietly() }
                } else {
                    Log.i(TAG, "Telefono identificato: ${decoded.name} ($remote)")
                }
                else -> controller.submitFromRemote(decoded)
            }
        }

        override fun onPong(pong: WebSocketFrame?) = Unit

        override fun onException(exception: IOException?) {
            // Timeout di lettura o connessione interrotta (telefono uscito dal Wi-Fi): normale.
            Log.i(TAG, "Connessione con $remote terminata: ${exception?.message}")
            release()
        }

        fun enqueue(json: String) {
            try {
                sender.execute {
                    try {
                        if (isOpen) send(json)
                    } catch (e: IOException) {
                        Log.i(TAG, "Invio a $remote fallito: ${e.message}")
                        closeQuietly()
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Executor già chiuso: il client è in chiusura, il messaggio non serve più.
            }
        }

        fun closeQuietly() {
            try {
                if (isOpen) close(WebSocketFrame.CloseCode.GoingAway, "chiusura", false)
            } catch (_: IOException) {
            }
            release()
        }

        private fun release() {
            if (clients.remove(this)) _clientCount.value = clients.size
            sender.shutdown()
        }
    }

    companion object {
        private const val TAG = "RemoteServer"

        /** Timeout di lettura dei socket: il telefono invia un ping ogni pochi secondi. */
        private const val SOCKET_TIMEOUT_MS = 15_000

        /** Blocco di lettura dei caricamenti: grande, perché la scrittura su USB è più rapida a blocchi ampi. */
        private const val UPLOAD_BUFFER = 256 * 1024

        /** Frequenza massima degli aggiornamenti di avanzamento verso TV e telefono. */
        private const val PROGRESS_INTERVAL_MS = 300L

        /**
         * Avvia il server sulla porta preferita o, se occupata, su una porta libera scelta dal
         * sistema (l'annuncio NSD e il beacon riportano comunque la porta effettiva).
         */
        fun start(
            context: Context,
            controller: BrowserController,
            files: FileOperations,
            playback: PlaybackController,
            logExporter: LogExporter,
            scope: CoroutineScope,
            serverName: String,
            serverId: String,
            appVersion: String,
        ): RemoteServer {
            val server = try {
                RemoteServer(Protocol.DEFAULT_PORT, context, controller, files, playback, logExporter, serverName, serverId, appVersion).also {
                    it.start(SOCKET_TIMEOUT_MS, false)
                }
            } catch (e: BindException) {
                Log.w(TAG, "Porta ${Protocol.DEFAULT_PORT} occupata, uso una porta libera", e)
                RemoteServer(0, context, controller, files, playback, logExporter, serverName, serverId, appVersion).also {
                    it.start(SOCKET_TIMEOUT_MS, false)
                }
            }
            server.startBroadcasting(scope)
            Log.i(TAG, "Server in ascolto sulla porta ${server.listeningPort}")
            return server
        }
    }
}
