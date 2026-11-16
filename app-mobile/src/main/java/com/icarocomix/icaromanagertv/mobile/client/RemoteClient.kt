package com.icarocomix.icaromanagertv.mobile.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.icarocomix.icaromanagertv.common.net.LocalNetwork
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.Listing
import com.icarocomix.icaromanagertv.common.protocol.ListingProgress
import com.icarocomix.icaromanagertv.common.protocol.PlaybackState
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import com.icarocomix.icaromanagertv.common.protocol.ProtocolCodec
import com.icarocomix.icaromanagertv.common.protocol.RemoteState
import com.icarocomix.icaromanagertv.common.protocol.ServerMessage
import com.icarocomix.icaromanagertv.mobile.discovery.TvDiscovery
import com.icarocomix.icaromanagertv.mobile.discovery.TvEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Stato del collegamento con la TV, mostrato nella barra del telefono. */
sealed class Connection {
    data object Searching : Connection()
    data class Connecting(val tv: TvEndpoint) : Connection()
    data class Connected(val tv: TvEndpoint, val serverName: String, val serverVersion: String) : Connection()
    data class Retrying(val tv: TvEndpoint, val delayMs: Long) : Connection()

    /** Errore che un nuovo tentativo non risolve (versioni incompatibili): serve un'azione. */
    data class Failed(val tv: TvEndpoint, val reason: String) : Connection()
}

/**
 * Collegamento con la TV: sceglie da solo la TV (l'ultima usata o la prima trovata), si collega
 * via WebSocket e si ricollega a oltranza in caso di caduta, con attesa crescente (0,5 → 8 s)
 * interrotta subito da eventi utili: rete Wi-Fi tornata, TV riapparsa, scelta dell'utente.
 *
 * Il collegamento caduto si rileva con i ping WebSocket ogni [PING_SECONDS] secondi: se la TV
 * non risponde OkHttp chiude il socket e parte la riconnessione.
 */
class RemoteClient(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val discovery = TvDiscovery(appContext)

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val _connection = MutableStateFlow<Connection>(Connection.Searching)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _listing = MutableStateFlow<Listing?>(null)
    val listing: StateFlow<Listing?> = _listing.asStateFlow()

    private val _state = MutableStateFlow<RemoteState?>(null)
    val state: StateFlow<RemoteState?> = _state.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackState.IDLE)
    @Volatile private var playbackTvId: String? = null

    /** Stato del player integrato della TV; resta IDLE con una TV precedente alla v1.1. */
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private val _progress = MutableStateFlow(ListingProgress.EMPTY)

    /** Parte vista dei file dell'elenco; resta vuota con una TV precedente alla v1.2. */
    val progress: StateFlow<ListingProgress> = _progress.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /** Sveglia del ciclo di riconnessione: interrompe l'attesa quando qualcosa cambia. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var socket: WebSocket? = null
    private var loopJob: Job? = null
    private var endpointsJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var preferredId: String? = prefs.getString(KEY_LAST_ID, null)
    private var failuresOnPreferred = 0

    // ------------------------------------------------------------------ ciclo di vita

    fun start() {
        if (loopJob?.isActive == true) return
        discovery.start()
        registerNetworkCallback()
        endpointsJob = scope.launch {
            // Nuova TV trovata o indirizzo cambiato: se non sono collegato provo subito.
            discovery.endpoints.collect { if (_connection.value !is Connection.Connected) wake.trySend(Unit) }
        }
        loopJob = scope.launch { connectionLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        endpointsJob?.cancel()
        endpointsJob = null
        socket?.close(1001, "app in background")
        socket = null
        discovery.stop()
        unregisterNetworkCallback()
        _connection.value = Connection.Searching
    }

    /** Scelta esplicita di una TV dall'elenco: diventa la preferita e mi ricollego a lei. */
    fun choose(tv: TvEndpoint) {
        preferredId = tv.id
        failuresOnPreferred = 0
        prefs.edit().putString(KEY_LAST_ID, tv.id).apply()
        val current = (_connection.value as? Connection.Connected)?.tv
        if (current?.id != tv.id) socket?.cancel()
        wake.trySend(Unit)
    }

    /** Invia un comando alla TV; false se non collegato. */
    fun send(message: ClientMessage): Boolean {
        val ws = socket ?: return false
        if (_connection.value !is Connection.Connected) return false
        return ws.send(ProtocolCodec.encode(message))
    }

    /**
     * Scarica in [target] il pacchetto ZIP dei log della TV collegata. Bloccante: va chiamato
     * fuori dal main thread. La TV prepara lo ZIP al momento (lancia logcat e comprime fino a
     * qualche MB), quindi uso un timeout di lettura ampio e niente ping, che qui non servono.
     * @throws TvLogsUnsupportedException se la TV ha un'app precedente alla v1.3
     * @throws IOException se la TV non è collegata o il download fallisce
     */
    fun downloadTvLogs(target: File) {
        val tv = (_connection.value as? Connection.Connected)?.tv ?: throw IOException("TV non collegata")
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(tv.host)
            .port(tv.port)
            .encodedPath(Protocol.LOGS_PATH)
            .addQueryParameter(Protocol.LOGS_ID_PARAM, tv.id)
            .build()
        val client = http.newBuilder()
            .readTimeout(LOGS_TIMEOUT_S, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) throw TvLogsUnsupportedException()
            if (!response.isSuccessful) {
                throw IOException("la TV ha risposto ${response.code}: ${response.body?.string()?.take(200).orEmpty()}")
            }
            val body = response.body ?: throw IOException("risposta vuota dalla TV")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        Log.i(TAG, "Log della TV scaricati: ${target.length()} byte")
    }

    // ------------------------------------------------------------------ riconnessione

    private suspend fun connectionLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val target = pickTarget()
            if (target == null) {
                _connection.value = Connection.Searching
                discovery.probeNow()
                waitWake(SEARCH_POLL_MS)
                continue
            }
            _connection.value = Connection.Connecting(target)
            val outcome = connectOnce(target)
            if (outcome.wasConnected) {
                attempt = 0
                failuresOnPreferred = 0
            } else {
                attempt++
                if (target.id == preferredId) failuresOnPreferred++
            }
            if (outcome.fatalReason != null) {
                // Riprovare non serve: aspetto un cambiamento (altra TV, rete, scelta utente).
                _connection.value = Connection.Failed(target, outcome.fatalReason)
                waitWake(FATAL_RETRY_MS)
                continue
            }
            val delayMs = backoff(attempt)
            _connection.value = Connection.Retrying(target, delayMs)
            waitWake(delayMs)
        }
    }

    private fun backoff(attempt: Int): Long =
        if (attempt == 0) 500L else minOf(MAX_BACKOFF_MS, 500L shl minOf(attempt, 4))

    private suspend fun waitWake(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    /**
     * TV da contattare: la preferita se visibile (a meno che fallisca ripetutamente e ce ne siano
     * altre), poi la prima trovata, infine l'ultimo indirizzo noto (riconnessione rapida
     * all'avvio, prima ancora che la ricerca risponda).
     */
    private fun pickTarget(): TvEndpoint? {
        val found = discovery.endpoints.value
        val preferred = preferredId?.let { found[it] }
        if (preferred != null && (failuresOnPreferred < 3 || found.size == 1)) return preferred
        found.values.firstOrNull { it.id != preferredId }?.let { return it }
        if (preferred != null) return preferred
        val host = prefs.getString(KEY_LAST_HOST, null) ?: return null
        val port = prefs.getInt(KEY_LAST_PORT, 0).takeIf { it > 0 } ?: return null
        return TvEndpoint(prefs.getString(KEY_LAST_ID, null) ?: "cache", prefs.getString(KEY_LAST_NAME, null) ?: host, host, port)
    }

    private data class Outcome(val wasConnected: Boolean, val fatalReason: String? = null)

    /** Un tentativo di collegamento: ritorna quando il socket si chiude (o non si apre). */
    private suspend fun connectOnce(tv: TvEndpoint): Outcome {
        val done = CompletableDeferred<Outcome>()
        var opened = false
        var fatal: String? = null
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(tv.host)
            .port(tv.port)
            .encodedPath(Protocol.WS_PATH)
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                socket = webSocket
                webSocket.send(ProtocolCodec.encode(ClientMessage.Hello(Protocol.VERSION, LocalNetwork.deviceName(appContext))))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val msg = ProtocolCodec.decodeServer(text)) {
                    is ServerMessage.Hello -> {
                        if (msg.protocol != Protocol.VERSION) {
                            fatal = "Versione del protocollo incompatibile (TV ${msg.protocol}, telefono ${Protocol.VERSION}): aggiorna entrambe le app."
                            webSocket.close(1000, "versione incompatibile")
                            return
                        }
                        val endpoint = tv.copy(id = msg.id.ifBlank { tv.id }, name = msg.name.ifBlank { tv.name })
                        remember(endpoint)
                        // Altra TV: azzero lo stato del player (una TV v1.0 non lo invierà mai).
                        // Riconnessione alla stessa TV: lo tengo, così una breve caduta del Wi-Fi
                        // non viene scambiata per l'avvio di una nuova riproduzione.
                        // Anche l'avanzamento: la seq di un'altra TV potrebbe coincidere per caso.
                        if (endpoint.id != playbackTvId) {
                            _playback.value = PlaybackState.IDLE
                            _progress.value = ListingProgress.EMPTY
                        }
                        playbackTvId = endpoint.id
                        _connection.value = Connection.Connected(endpoint, msg.name, msg.appVersion)
                    }
                    is ServerMessage.ListingMsg -> _listing.value = msg.listing
                    is ServerMessage.StateMsg -> _state.value = msg.state
                    is ServerMessage.PlaybackMsg -> _playback.value = msg.playback
                    is ServerMessage.ProgressMsg -> _progress.value = msg.progress
                    is ServerMessage.Toast -> _toasts.tryEmit(msg.text)
                    is ServerMessage.Error -> fatal = msg.text
                    null -> Log.w(TAG, "Messaggio non riconosciuto dalla TV")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Outcome(opened, fatal))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(TAG, "Collegamento con ${tv.host}:${tv.port} non riuscito o caduto: ${t.message}")
                done.complete(Outcome(opened, fatal))
            }
        }
        val ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
        return try {
            done.await()
        } finally {
            // Anche alla cancellazione della coroutine (app in background) chiudo il socket.
            ws.cancel()
            if (socket === ws) socket = null
        }
    }

    /** Memorizzo l'ultima TV per ricollegarmi subito al prossimo avvio. */
    private fun remember(tv: TvEndpoint) {
        preferredId = tv.id
        prefs.edit()
            .putString(KEY_LAST_ID, tv.id)
            .putString(KEY_LAST_NAME, tv.name)
            .putString(KEY_LAST_HOST, tv.host)
            .putInt(KEY_LAST_PORT, tv.port)
            .apply()
    }

    // ------------------------------------------------------------------ rete

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Wi-Fi tornato: riavvio la ricerca e interrompo l'attesa del backoff.
                discovery.restart()
                wake.trySend(Unit)
            }

            override fun onLost(network: Network) {
                // Il socket è morto con la rete: lo chiudo subito invece di attendere i ping.
                socket?.cancel()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Callback di rete non registrata", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private companion object {
        const val TAG = "RemoteClient"
        const val PREFS = "remote"
        const val KEY_LAST_ID = "last_id"
        const val KEY_LAST_NAME = "last_name"
        const val KEY_LAST_HOST = "last_host"
        const val KEY_LAST_PORT = "last_port"
        const val PING_SECONDS = 4L
        const val MAX_BACKOFF_MS = 8_000L
        const val SEARCH_POLL_MS = 3_000L
        const val FATAL_RETRY_MS = 30_000L
        const val LOGS_TIMEOUT_S = 60L
    }
}

/** La TV non espone i log: ha un'app precedente alla v1.3. */
class TvLogsUnsupportedException : IOException("la TV ha una versione dell'app senza esportazione dei log")
