package com.icarocomix.icaromanagertv.tv.server

import android.util.Log
import com.icarocomix.icaromanagertv.common.net.LocalNetwork
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import com.icarocomix.icaromanagertv.common.protocol.ProtocolCodec
import com.icarocomix.icaromanagertv.common.protocol.ServerMessage
import com.icarocomix.icaromanagertv.tv.controller.BrowserController
import com.icarocomix.icaromanagertv.tv.diagnostics.LogExporter
import com.icarocomix.icaromanagertv.tv.playback.PlaybackController
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
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
    private val controller: BrowserController,
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
    private var broadcastJob: Job? = null

    private fun startBroadcasting(scope: CoroutineScope) {
        broadcastJob = scope.launch {
            launch {
                controller.listing.collect { listing ->
                    val json = ProtocolCodec.encode(ServerMessage.ListingMsg(listing))
                    lastListingJson = json
                    broadcast(json)
                }
            }
            launch {
                controller.state.collect { state ->
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
                controller.progress.collect { progress ->
                    val json = ProtocolCodec.encode(ServerMessage.ProgressMsg(progress))
                    lastProgressJson = json
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

        /**
         * Avvia il server sulla porta preferita o, se occupata, su una porta libera scelta dal
         * sistema (l'annuncio NSD e il beacon riportano comunque la porta effettiva).
         */
        fun start(
            controller: BrowserController,
            playback: PlaybackController,
            logExporter: LogExporter,
            scope: CoroutineScope,
            serverName: String,
            serverId: String,
            appVersion: String,
        ): RemoteServer {
            val server = try {
                RemoteServer(Protocol.DEFAULT_PORT, controller, playback, logExporter, serverName, serverId, appVersion).also {
                    it.start(SOCKET_TIMEOUT_MS, false)
                }
            } catch (e: BindException) {
                Log.w(TAG, "Porta ${Protocol.DEFAULT_PORT} occupata, uso una porta libera", e)
                RemoteServer(0, controller, playback, logExporter, serverName, serverId, appVersion).also {
                    it.start(SOCKET_TIMEOUT_MS, false)
                }
            }
            server.startBroadcasting(scope)
            Log.i(TAG, "Server in ascolto sulla porta ${server.listeningPort}")
            return server
        }
    }
}
