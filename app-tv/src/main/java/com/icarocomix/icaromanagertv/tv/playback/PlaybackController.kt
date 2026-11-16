package com.icarocomix.icaromanagertv.tv.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaSession
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.PlaybackState
import com.icarocomix.icaromanagertv.common.protocol.PlayerAction
import com.icarocomix.icaromanagertv.common.protocol.TvView
import com.icarocomix.icaromanagertv.tv.R
import com.icarocomix.icaromanagertv.tv.TvApp
import com.icarocomix.icaromanagertv.tv.controller.MediaLauncher
import com.icarocomix.icaromanagertv.tv.storage.Node
import com.icarocomix.icaromanagertv.tv.storage.NodeRef
import com.icarocomix.icaromanagertv.tv.ui.TvMainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Player integrato della TV, unico per tutta l'app: lo stesso [ExoPlayer] viene mostrato a tutto
 * schermo o nella miniatura sopra il file manager, quindi passare da una vista all'altra non
 * interrompe né ricarica il video.
 *
 * Regole di thread: ExoPlayer va usato solo dal thread su cui è stato creato. Tutto lo stato di
 * questa classe vive sul main thread; i metodi pubblici sono chiamabili da qualunque thread
 * (controller del file manager su IO, server, UI) perché rimbalzano sul main. L'archivio delle
 * posizioni lavora su un dispatcher seriale dedicato, così scritture e cancellazioni restano
 * nell'ordine in cui le ho chieste.
 *
 * Playlist: istantanea dei file della stessa famiglia (video o audio) visibili nell'elenco al
 * momento dell'apertura. Navigare nel file manager durante la riproduzione non la modifica.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class PlaybackController(
    context: Context,
    private val store: ProgressStore,
    private val launcher: MediaLauncher,
    private val scope: CoroutineScope,
    private val onNowPlaying: (String?) -> Unit,
    private val onToast: (String) -> Unit,
) {

    private val app = context.applicationContext

    /** Volume di sistema della TV, comandato dal telefono (v1.4). */
    private val volume = VolumeControl(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val db = Dispatchers.IO.limitedParallelism(1)

    private val _player = MutableStateFlow<ExoPlayer?>(null)

    /**
     * Sessione multimediale del player corrente: segnala al sistema della TV che c'è una
     * riproduzione attiva e gli instrada comandi vocali e tasti multimediali globali.
     */
    private var session: MediaSession? = null

    /** Player da agganciare alla PlayerView della TV; null quando non c'è nulla in riproduzione. */
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState(autoplay = prefs.getBoolean(KEY_AUTOPLAY, true)))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // ---- Stato interno: solo main thread.
    private var playlist: List<Node> = emptyList()
    private var view = TvView.FULLSCREEN
    private var autoplay = _state.value.autoplay
    private var resumeFromMs = -1L
    private var resumeJob: Job? = null
    private var tickJob: Job? = null

    /** Cresce a ogni cambio di file: scarta i risultati asincroni (lettura posizione) superati. */
    private var itemToken = 0

    /** Indice e ultima posizione nota del file corrente, per salvarlo anche dopo il cambio di file. */
    private var trackedIndex = -1
    private var trackedPositionMs = 0L
    private var trackedDurationMs = -1L
    private var tracksChecked = false

    /**
     * Vero dal cambio di file fino alla lettura della sua posizione salvata. In quella finestra
     * non salvo nulla: la pausa automatica del nuovo file (posizione ~0) cancellerebbe la voce
     * prima che io la legga.
     */
    private var resumeCheckPending = false

    private var uiVisible = false
    private var playWhenVisible = false

    // ------------------------------------------------------------------ API pubblica

    /** Avvia [items] dalla posizione [startIndex], sostituendo l'eventuale riproduzione in corso. */
    fun play(items: List<Node>, startIndex: Int) {
        if (items.isEmpty() || startIndex !in items.indices) return
        onMain { startPlaylist(items, startIndex) }
    }

    /** Comandi del player arrivati da telefono, telecomando o interfaccia della TV. */
    fun handle(message: ClientMessage) {
        onMain {
            when (message) {
                is ClientMessage.Player -> transport(message.action)
                is ClientMessage.SeekTo -> seekTo(message.positionMs)
                is ClientMessage.Autoplay -> setAutoplay(message.enabled)
                is ClientMessage.Resume -> answerResume(message.fromSaved)
                is ClientMessage.Layout -> setView(message.view)
                is ClientMessage.Volume -> {
                    volume.set(message.level)
                    publish()
                }
                else -> Unit
            }
        }
    }

    /** L'activity della TV è visibile: posso riprodurre (avvii rimasti in attesa compresi). */
    fun onUiStarted() {
        onMain {
            uiVisible = true
            // Se sto ancora leggendo la posizione salvata non parto: lo farà la verifica stessa.
            if (playWhenVisible && resumeFromMs < 0 && !resumeCheckPending) _player.value?.play()
            playWhenVisible = false
            publish()
        }
    }

    /**
     * L'activity non è più visibile (tasto Home, altra app): metto in pausa e salvo. Senza una
     * superficie il video non si vede, e l'audio da solo in background non è il comportamento
     * atteso su una TV.
     */
    fun onUiStopped() {
        onMain {
            uiVisible = false
            if (_player.value?.playWhenReady == true) Log.i(TAG, "Activity della TV non più visibile: metto in pausa")
            _player.value?.let { p ->
                if (p.playWhenReady) p.pause()
                saveCurrent()
            }
            publish()
        }
    }

    // ------------------------------------------------------------------ playlist e avvio

    private fun startPlaylist(items: List<Node>, startIndex: Int) {
        Log.i(TAG, "Avvio playlist: ${items.size} file, dal n. ${startIndex + 1} «${items[startIndex].name}»")
        saveCurrent()
        resumeCheckPending = true
        val p = ensurePlayer()
        playlist = items
        view = TvView.FULLSCREEN
        trackedIndex = startIndex
        trackedPositionMs = 0
        trackedDurationMs = -1
        clearPrompt()
        p.playWhenReady = false
        p.setMediaItems(items.map(::mediaItemOf), startIndex, 0L)
        p.prepare()
        onNowPlaying(items[startIndex].name)
        startTicking()
        publish()
        bringUiToFront()
        checkResume(startIndex)
    }

    private fun ensurePlayer(): ExoPlayer {
        _player.value?.let { return it }
        // Fallback del decoder: se il decoder hardware preferito non si inizializza (frequente
        // sulle TV con profili HEVC/VP9 limitati) ExoPlayer prova il successivo.
        val renderers = DefaultRenderersFactory(app).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(app, renderers)
            .setSeekBackIncrementMs(REWIND_MS)
            .setSeekForwardIncrementMs(FORWARD_MS)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        p.pauseAtEndOfMediaItems = !autoplay
        // Wake lock della CPU (e del Wi-Fi non serve: i file sono locali) mentre riproduce: senza,
        // alcune TV abbassano le frequenze o sospendono la CPU con lo schermo "inattivo" e la
        // riproduzione si inceppa. Lo schermo acceso lo garantisce invece l'activity con
        // FLAG_KEEP_SCREEN_ON; ExoPlayer rilascia il lock da solo in pausa e alla chiusura.
        p.setWakeMode(C.WAKE_MODE_LOCAL)
        // Traccia dettagliata di ogni evento del player (formati, decoder, errori, salti di
        // frame) nei log esportabili: è ciò che serve per capire un problema su una TV remota.
        p.addAnalyticsListener(EventLogger(TAG_EVENTS))
        p.addListener(listener)
        _player.value = p
        session = createSession(p)
        Log.i(TAG, "Player creato")
        return p
    }

    /**
     * La sessione espone al sistema un [ForwardingPlayer]: i comandi esterni (assistente vocale,
     * scheda "In riproduzione", tasti multimediali con l'app in background) passano dalle stesse
     * regole dei comandi del telefono invece di agire direttamente su ExoPlayer.
     */
    private fun createSession(p: ExoPlayer): MediaSession? {
        val sessionPlayer = object : ForwardingPlayer(p) {
            override fun play() {
                // Domanda di ripresa aperta: "play" vale come "riprendi dal punto salvato".
                if (resumeFromMs >= 0) answerResume(fromSaved = true) else startOrDefer(p)
            }

            override fun stop() {
                // Rimando la chiusura: rilascerei player e sessione dentro una loro callback.
                scope.launch(Dispatchers.Main) { close() }
            }
        }
        val activity = PendingIntent.getActivity(
            app,
            0,
            Intent(app, TvMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return try {
            MediaSession.Builder(app, sessionPlayer)
                .setId(SESSION_ID)
                .setSessionActivity(activity)
                .build()
        } catch (e: IllegalStateException) {
            // Sessione con lo stesso id non ancora rilasciata: il player funziona anche senza.
            Log.w(TAG, "Sessione multimediale non creata", e)
            null
        }
    }

    private fun mediaItemOf(node: Node): MediaItem {
        // In-app non serve FileProvider: il processo ha già il permesso di lettura sul file o
        // sull'albero SAF, e ExoPlayer legge sia file:// sia content://.
        val uri: Uri = when (val ref = node.ref) {
            is NodeRef.Posix -> Uri.fromFile(ref.file)
            is NodeRef.Saf -> DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, ref.documentId)
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(ProgressStore.keyOf(node))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(node.name).build())
            .build()
    }

    /** Porta in primo piano l'activity della TV se la riproduzione è partita dal telefono. */
    private fun bringUiToFront() {
        if (uiVisible) return
        playWhenVisible = true
        val intent = Intent(app, TvMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            app.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Activity della TV non riportata in primo piano", e)
        }
        // Da Android 10 l'avvio da background viene scartato in silenzio senza il permesso di
        // sovrapposizione: avviso l'utente invece di lasciarlo davanti a una TV che non reagisce.
        launcher.backgroundWarning()?.let(onToast)
    }

    // ------------------------------------------------------------------ ripresa

    /**
     * Controlla se il file [index] ha una posizione salvata. Se sì metto in pausa sul punto
     * salvato e pubblico la domanda (TV e telefono la mostrano, risponde il primo); senza
     * risposta riprendo da solo dopo [RESUME_TIMEOUT_MS].
     */
    private fun checkResume(index: Int) {
        val node = playlist.getOrNull(index) ?: return
        val token = ++itemToken
        tracksChecked = false
        resumeCheckPending = true
        scope.launch(Dispatchers.Main.immediate) {
            val saved = withContext(db) { store.load(node) }
            val p = _player.value ?: return@launch
            if (token != itemToken) return@launch
            if (saved == null || !ProgressStore.isWorthResuming(saved.positionMs, saved.durationMs)) {
                resumeCheckPending = false
                startOrDefer(p)
                return@launch
            }
            // Imposto la domanda prima di pausa e salto: i callback che ne derivano non salvano.
            resumeFromMs = saved.positionMs
            resumeCheckPending = false
            p.pause()
            p.seekTo(index, saved.positionMs)
            resumeJob?.cancel()
            resumeJob = scope.launch(Dispatchers.Main.immediate) {
                delay(RESUME_TIMEOUT_MS)
                if (token == itemToken) answerResume(fromSaved = true)
            }
            publish()
        }
    }

    private fun answerResume(fromSaved: Boolean) {
        val p = _player.value ?: return
        if (resumeFromMs < 0) return
        val position = resumeFromMs
        clearPrompt()
        if (fromSaved) {
            p.seekTo(position)
        } else {
            p.seekTo(0)
            playlist.getOrNull(p.currentMediaItemIndex)?.let { node -> scope.launch(db) { store.clear(node) } }
        }
        startOrDefer(p)
        publish()
    }

    private fun clearPrompt() {
        resumeFromMs = -1
        resumeJob?.cancel()
        resumeJob = null
    }

    private fun startOrDefer(p: ExoPlayer) {
        if (uiVisible) p.play() else playWhenVisible = true
        publish()
    }

    // ------------------------------------------------------------------ comandi

    private fun transport(action: PlayerAction) {
        // Il volume è della TV, non del file: lo regolo anche senza nulla in riproduzione.
        if (action.isVolume) {
            when (action) {
                PlayerAction.VOLUME_UP -> volume.raise()
                PlayerAction.VOLUME_DOWN -> volume.lower()
                else -> volume.toggleMute()
            }
            publish()
            return
        }
        val p = _player.value ?: return
        when (action) {
            PlayerAction.PLAY_PAUSE -> when {
                resumeFromMs >= 0 -> answerResume(fromSaved = true)
                p.playWhenReady -> p.pause()
                else -> {
                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                    startOrDefer(p)
                }
            }
            PlayerAction.STOP -> close()
            PlayerAction.SEEK_START -> seekTo(0)
            // "Fine": gli ultimi secondi del file, così si vedono i titoli di coda e
            // l'autoplay (se attivo) passa al successivo con il flusso naturale.
            PlayerAction.SEEK_END -> if (p.duration != C.TIME_UNSET) seekTo((p.duration - END_JUMP_MS).coerceAtLeast(0))
            PlayerAction.REWIND -> seekTo((p.currentPosition - REWIND_MS).coerceAtLeast(0))
            PlayerAction.FORWARD -> seekTo(p.currentPosition + FORWARD_MS)
            PlayerAction.PREVIOUS -> if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else seekTo(0)
            PlayerAction.NEXT -> if (p.hasNextMediaItem()) p.seekToNextMediaItem() else onToast(app.getString(R.string.player_last_item))
            PlayerAction.VOLUME_UP, PlayerAction.VOLUME_DOWN, PlayerAction.MUTE -> Unit
        }
        publish()
    }

    private fun seekTo(positionMs: Long) {
        val p = _player.value ?: return
        // Un salto esplicito vale come risposta alla domanda di ripresa: l'utente ha scelto lui.
        if (resumeFromMs >= 0) {
            clearPrompt()
            startOrDefer(p)
        }
        val duration = p.duration
        val target = if (duration != C.TIME_UNSET) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        p.seekTo(target)
        publish()
    }

    private fun setAutoplay(enabled: Boolean) {
        autoplay = enabled
        prefs.edit().putBoolean(KEY_AUTOPLAY, enabled).apply()
        // Senza autoplay ExoPlayer si ferma alla fine di ogni file invece di passare al successivo.
        _player.value?.pauseAtEndOfMediaItems = !enabled
        publish()
    }

    private fun setView(newView: TvView) {
        if (_player.value == null || view == newView) return
        view = newView
        publish()
    }

    /** Chiude la riproduzione e libera il decoder (memoria preziosa sulle TV economiche). */
    private fun close() {
        val p = _player.value ?: return
        Log.i(TAG, "Chiusura del player")
        saveCurrent()
        clearPrompt()
        itemToken++
        tickJob?.cancel()
        tickJob = null
        p.removeListener(listener)
        // Prima la sessione, poi il player che le fa da base.
        session?.release()
        session = null
        p.release()
        _player.value = null
        playlist = emptyList()
        trackedIndex = -1
        resumeCheckPending = false
        playWhenVisible = false
        view = TvView.FULLSCREEN
        onNowPlaying(null)
        publish()
    }

    // ------------------------------------------------------------------ eventi del player

    private val listener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            val p = _player.value ?: return
            // Il file precedente: concluso se il passaggio è automatico, altrimenti salvo il
            // punto raggiunto (l'utente è passato oltre a metà visione). Se lo lascio mentre
            // era ancora in attesa della scelta di ripresa non è stato visto: resta com'era.
            val untouched = resumeFromMs >= 0 || resumeCheckPending
            playlist.getOrNull(trackedIndex)?.let { previous ->
                when {
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> scope.launch(db) { store.clear(previous) }
                    !untouched -> persist(previous, trackedPositionMs, trackedDurationMs)
                }
            }
            val index = p.currentMediaItemIndex
            Log.i(TAG, "Passaggio al file n. ${index + 1}/${playlist.size} «${playlist.getOrNull(index)?.name}» (motivo $reason)")
            trackedIndex = index
            trackedPositionMs = 0
            trackedDurationMs = -1
            clearPrompt()
            resumeCheckPending = true
            playlist.getOrNull(index)?.let { onNowPlaying(it.name) }
            // Fermo un attimo il nuovo file finché non so se ha una posizione da riprendere.
            p.pause()
            checkResume(index)
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // Fine dell'ultimo file con autoplay attivo: playlist conclusa.
                playlist.getOrNull(trackedIndex)?.let { node -> scope.launch(db) { store.clear(node) } }
                trackedIndex = -1
                onToast(app.getString(R.string.player_playlist_done))
                close()
                return
            }
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Il motivo distingue pausa dell'utente, perdita del focus audio (es. altra app o
            // salvaschermo con suoni) e fine file: è il dato chiave per le pause "inspiegabili".
            Log.i(TAG, "playWhenReady=$playWhenReady (motivo ${playWhenReadyReason(reason)})")
            // Pausa (dell'utente, di fine file o per perdita del focus audio): salvo subito.
            if (!playWhenReady) saveCurrent()
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onTracksChanged(tracks: Tracks) {
            if (tracksChecked || tracks.isEmpty) return
            tracksChecked = true
            // ExoPlayer disattiva in silenzio le tracce che nessun decoder del televisore sa
            // gestire (tipico: audio DTS/AC3 su TV senza licenza). Un film muto non serve:
            // passo il file al player esterno, che spesso ha decoder software.
            val audioLost = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
            val videoLost = tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
            if (audioLost || videoLost) {
                handOffToExternal(if (audioLost) R.string.player_audio_unsupported else R.string.player_video_unsupported)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Errore di riproduzione (${error.errorCodeName})", error)
            if (error.errorCode in UNSUPPORTED_ERRORS) {
                handOffToExternal(R.string.player_format_unsupported)
            } else {
                onToast(app.getString(R.string.player_error, error.errorCodeName))
                close()
            }
        }
    }

    /** Formato non gestibile dal player integrato: apro il file corrente con un player esterno. */
    private fun handOffToExternal(reasonRes: Int) {
        val node = playlist.getOrNull(_player.value?.currentMediaItemIndex ?: -1) ?: return
        onToast(app.getString(reasonRes, node.name))
        close()
        val result = launcher.open(node)
        if (result.started) onNowPlaying(node.name)
        result.message?.let(onToast)
    }

    // ------------------------------------------------------------------ avanzamento e salvataggi

    /** Ogni secondo pubblico la posizione; ogni [SAVE_INTERVAL_MS] la salvo se sta riproducendo. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch(Dispatchers.Main.immediate) {
            var sinceSave = 0L
            while (isActive) {
                delay(TICK_MS)
                val p = _player.value ?: break
                track(p)
                sinceSave += TICK_MS
                if (p.isPlaying && sinceSave >= SAVE_INTERVAL_MS) {
                    sinceSave = 0
                    saveCurrent()
                }
                publish()
            }
        }
    }

    private fun track(p: ExoPlayer) {
        if (p.currentMediaItemIndex != trackedIndex) return
        trackedPositionMs = p.currentPosition
        trackedDurationMs = if (p.duration == C.TIME_UNSET) -1 else p.duration
    }

    private fun saveCurrent() {
        val p = _player.value ?: return
        // Durante la domanda di ripresa la posizione del player è quella salvata: nulla di nuovo.
        if (resumeFromMs >= 0 || resumeCheckPending) return
        track(p)
        playlist.getOrNull(trackedIndex)?.let { persist(it, trackedPositionMs, trackedDurationMs) }
    }

    private fun persist(node: Node, positionMs: Long, durationMs: Long) {
        scope.launch(db) { store.save(node, positionMs, durationMs) }
    }

    private fun publish() {
        val p = _player.value
        // Il volume viaggia anche a riposo: costa una lettura e tiene il telefono allineato.
        val audio = volume.snapshot()
        if (p == null) {
            _state.value = PlaybackState(autoplay = autoplay, volume = audio.level, volumeMax = audio.max, muted = audio.muted)
            return
        }
        val index = p.currentMediaItemIndex
        val node = playlist.getOrNull(index)
        _state.value = PlaybackState(
            active = true,
            title = node?.name,
            kind = node?.kind ?: _state.value.kind,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = if (p.duration == C.TIME_UNSET) -1 else p.duration,
            // "playing" è l'intenzione (play/pausa), non lo stato istantaneo del buffer: il
            // pulsante del telefono non deve lampeggiare a ogni ricarica.
            playing = (p.playWhenReady || playWhenVisible) && resumeFromMs < 0,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            index = index,
            count = playlist.size,
            autoplay = autoplay,
            view = view,
            resumeFromMs = resumeFromMs,
            volume = audio.level,
            volumeMax = audio.max,
            muted = audio.muted,
        )
    }

    private fun playWhenReadyReason(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "comando"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "perdita del focus audio"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "uscita audio scollegata"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remoto"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "fine del file"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "soppresso troppo a lungo"
        else -> reason.toString()
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            // Un errore imprevisto non deve far cadere il processo (e con lui il server).
            try {
                block()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Comando del player non eseguito", e)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Comando del player non valido", e)
            }
        }
    }

    init {
        // Tengo l'archivio entro i limiti a ogni avvio dell'app, fuori dal main thread.
        scope.launch(db) { store.prune() }
    }

    companion object {
        private const val TAG = "PlaybackController"
        private const val TAG_EVENTS = "PlayerEvents"
        private const val SESSION_ID = "icaromanagertv-player"
        private const val PREFS = "playback"
        private const val KEY_AUTOPLAY = "autoplay"
        private const val TICK_MS = 1_000L
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val REWIND_MS = 10_000L
        private const val FORWARD_MS = 30_000L
        private const val END_JUMP_MS = 5_000L
        const val RESUME_TIMEOUT_MS = 15_000L

        /** Errori che indicano un formato o un codec non gestibile dal player integrato. */
        private val UNSUPPORTED_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )
    }
}
