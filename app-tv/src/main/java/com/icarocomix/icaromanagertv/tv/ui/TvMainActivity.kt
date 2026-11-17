package com.icarocomix.icaromanagertv.tv.ui

import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.Listing
import com.icarocomix.icaromanagertv.common.protocol.PlaybackState
import com.icarocomix.icaromanagertv.common.protocol.PlayerAction
import com.icarocomix.icaromanagertv.common.protocol.RemoteKey
import com.icarocomix.icaromanagertv.common.protocol.RemoteState
import com.icarocomix.icaromanagertv.common.protocol.TvView
import com.icarocomix.icaromanagertv.common.search.SearchText
import com.icarocomix.icaromanagertv.common.ui.CreditsDialog
import com.icarocomix.icaromanagertv.common.ui.CrumbBar
import com.icarocomix.icaromanagertv.common.ui.EntryAdapter
import com.icarocomix.icaromanagertv.common.util.TimeFormat
import com.icarocomix.icaromanagertv.tv.R
import com.icarocomix.icaromanagertv.tv.TvApp
import com.icarocomix.icaromanagertv.tv.playback.PlaybackController
import com.icarocomix.icaromanagertv.tv.service.RemoteServerService
import com.icarocomix.icaromanagertv.tv.storage.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import com.icarocomix.icaromanagertv.common.R as CommonR

/**
 * Schermata della TV. Non possiede stato: mostra quello del [com.icarocomix.icaromanagertv.tv.controller.BrowserController]
 * e gli invia gli stessi comandi del telefono. Ogni azione fatta qui (telecomando, tastiera TV)
 * arriva quindi anche al telefono e viceversa.
 */
class TvMainActivity : AppCompatActivity() {

    private val app get() = application as TvApp
    private val controller get() = app.controller
    private val playback get() = app.playback

    private lateinit var list: RecyclerView
    private lateinit var adapter: EntryAdapter
    private lateinit var search: EditText
    private lateinit var recursive: CheckBox
    private lateinit var crumbBar: CrumbBar
    private lateinit var message: TextView
    private lateinit var empty: TextView
    private lateinit var searching: ProgressBar
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusAddress: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var btnPermissions: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnBackToPlayer: Button
    private lateinit var btnClosePlayer: Button
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var miniHint: TextView
    private lateinit var resumePanel: View
    private lateinit var resumeMessage: TextView
    private lateinit var resumeCountdown: TextView
    private lateinit var resumeYes: Button

    // Sincronizzazione del campo di ricerca: evito che l'eco di una query vecchia (inviata da
    // qui un attimo prima) sovrascriva il testo che l'utente sta ancora digitando.
    private var applyingRemote = false
    private var lastSentQuery: String? = null
    private var lastSentAt = 0L
    private var lastSelected = -1
    private var lastSeq = -1
    private var hadRuntimePermission = false

    // Ultima disposizione applicata al player: evito di rifare layout e focus a ogni secondo.
    private var appliedActive = false
    private var appliedView: TvView? = null
    private var appliedResume = false
    private var resumeShownAt = 0L

    /** Stato attuale di FLAG_KEEP_SCREEN_ON sulla finestra (evito di riapplicarlo ogni secondo). */
    private var keepingScreenOn = false
    private var exportingLogs = false

    /** Thread seriale per l'iniezione dei tasti del pad del telefono (vedi [injectKey]). */
    private val keyInjector = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tv-remote-keys").apply { isDaemon = true }
    }
    private val instrumentation = Instrumentation()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPanelButtons()
        if (StorageAccess.hasRuntime(this)) {
            controller.onVolumesChanged()
            offerFullAccessOnce()
        } else {
            toast(getString(R.string.perm_denied))
        }
    }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            // Rendo persistente l'autorizzazione: la cartella resta tra gli archivi ai riavvii.
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            controller.onVolumesChanged()
            toast(getString(R.string.saf_added))
        } catch (e: SecurityException) {
            toast(getString(R.string.error_generic, e.message ?: "SecurityException"))
        }
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPanelButtons()
        controller.submit(ClientMessage.Refresh)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv)
        bindViews()
        // Il servizio avvia server e annuncio; il controller è dell'Application, quindi l'avvio
        // qui è solo una garanzia (idempotente) per avere subito l'elenco a schermo.
        RemoteServerService.start(this)
        controller.start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(controller.listing, controller.state, ::Pair).collect { (l, s) -> render(l, s) } }
                // Avanzamento a parte: l'adattatore lo applica solo quando la sequenza coincide
                // con l'elenco mostrato, quindi l'ordine di arrivo rispetto all'elenco è indifferente.
                launch { controller.progress.collect { adapter.submitProgress(it) } }
                launch { app.serverInfo.collect(::renderStatus) }
                launch { controller.toasts.collect(::toast) }
                launch { playback.player.collect(::attachPlayer) }
                launch { playback.state.collect(::renderPlayback) }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            // A schermo intero "Indietro" riduce il video a miniatura: non lo ferma mai.
            if (playback.state.value.active && playback.state.value.view == TvView.FULLSCREEN) {
                controller.submit(ClientMessage.Layout(TvView.MINI))
                return@addCallback
            }
            // Con il video già in miniatura il secondo "Indietro" esce dall'app (il player va in
            // pausa in onStop): non lo uso per risalire le cartelle, altrimenti dentro una
            // sottocartella servirebbero tante pressioni quanti sono i livelli. Per risalire resta
            // il tasto SINISTRA.
            if (playback.state.value.active && playback.state.value.view == TvView.MINI) {
                finish()
                return@addCallback
            }
            val state = controller.state.value
            val listing = controller.listing.value
            if (state.query.isNotEmpty() || listing.crumbs.isNotEmpty() || listing.searchResults) {
                controller.submit(ClientMessage.Key(RemoteKey.BACK))
            } else {
                // Esco dalla schermata ma il servizio resta attivo: il telefono continua a
                // vedere la TV. Per spegnere tutto c'è il pulsante dedicato.
                finish()
            }
        }

        hadRuntimePermission = StorageAccess.hasRuntime(this)
        if (savedInstanceState == null) ensurePermissions()
    }

    override fun onStart() {
        super.onStart()
        playback.onUiStarted()
        controller.remoteKeyTarget = remoteKeyTarget
    }

    override fun onStop() {
        // Tolgo il destinatario solo se è ancora il mio: un'altra istanza dell'activity può
        // averlo già sostituito con il proprio (onStart della nuova prima di onStop della vecchia).
        if (controller.remoteKeyTarget === remoteKeyTarget) controller.remoteKeyTarget = null
        // Senza superficie il video non si vede: il player va in pausa e salva la posizione.
        attachPlayer(null)
        playback.onUiStopped()
        super.onStop()
    }

    /**
     * Tasti multimediali del telecomando (play/pausa, avanti, successivo…): li gestisco qui in
     * qualunque vista, così funzionano anche con il video in miniatura e passano dallo stesso
     * percorso dei comandi del telefono.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val state = playback.state.value
        val action = if (state.active) MEDIA_KEYS[event.keyCode] else null
        if (action != null) {
            // PLAY e PAUSE dedicati (telecomandi con due tasti) non devono invertire lo stato.
            val redundant = (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY && state.playing) ||
                (event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE && !state.playing)
            if (event.action == KeyEvent.ACTION_UP && !redundant) controller.submit(ClientMessage.Player(action))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        keyInjector.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ pad del telefono

    /** Chiamato dai thread del server: riporto il tasto sul main thread, dove vive la UI. */
    private val remoteKeyTarget: (RemoteKey) -> Unit = { key -> runOnUiThread { onRemoteKey(key) } }

    /**
     * Tasto del pad del telefono con l'interfaccia della TV in primo piano. Nell'elenco conservo
     * la semantica di sempre del pad (SU/GIÙ spostano la selezione, SINISTRA risale di cartella,
     * DESTRA/OK aprono, INDIETRO annulla la ricerca o risale): funziona anche se il focus non è
     * sull'elenco ed è la stessa del servizio senza interfaccia. Altrove (player a schermo
     * intero, domanda di ripresa, pannello laterale, ricerca, finestre di dialogo) il tasto
     * diventa un vero evento D-pad, identico a quello del telecomando della TV.
     */
    private fun onRemoteKey(key: RemoteKey) {
        if (isListNavigation(key)) {
            controller.submit(ClientMessage.Key(key))
        } else {
            injectKey(REMOTE_KEY_CODES.getValue(key))
        }
    }

    private fun isListNavigation(key: RemoteKey): Boolean {
        val pb = playback.state.value
        if (pb.resumePending || (pb.active && pb.view == TvView.FULLSCREEN)) return false
        // Una finestra di dialogo (o un'altra finestra) ha il focus: i tasti vanno a lei.
        if (!hasWindowFocus()) return false
        val focus = currentFocus
        if (focus != null && !list.hasFocus()) return false
        // Alla radice senza ricerca SINISTRA non ha cartelle da risalire: la lascio passare come
        // D-pad, così dal telefono si raggiunge il pannello laterale come con il telecomando.
        if (key == RemoteKey.LEFT) {
            val listing = controller.listing.value
            val atRoot = listing.crumbs.isEmpty() && !listing.searchResults && controller.state.value.query.isEmpty()
            if (atRoot) return false
        }
        return true
    }

    /**
     * Inietto il tasto come evento di input di sistema: raggiunge la finestra che ha il focus
     * (activity o dialog) seguendo lo stesso percorso del telecomando fisico, compresa la
     * gestione del focus delle viste, i controlli Media3 e il callback di "Indietro". Un'app può
     * iniettare eventi solo nelle proprie finestre, ed è esattamente il caso: il tasto arriva
     * solo con l'activity in primo piano. L'iniezione sincrona non è ammessa sul main thread,
     * quindi uso un thread dedicato e seriale (l'ordine dei tasti va preservato).
     */
    private fun injectKey(keyCode: Int) {
        if (keyInjector.isShutdown) return
        keyInjector.execute {
            try {
                instrumentation.sendKeyDownUpSync(keyCode)
            } catch (e: Exception) {
                // Finestra di un'altra app in primo piano (es. permessi di sistema) o iniezione
                // negata dal produttore: se la mia finestra ha comunque il focus consegno il
                // tasto direttamente all'activity, altrimenti lo scarto.
                Log.w(TAG, "Iniezione del tasto $keyCode non riuscita: ${e.message}")
                runOnUiThread { dispatchDirectly(keyCode) }
            }
        }
    }

    private fun dispatchDirectly(keyCode: Int) {
        if (!hasWindowFocus()) return
        val now = SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun onResume() {
        super.onResume()
        refreshPanelButtons()
        // Permesso concesso dalle impostazioni di sistema mentre eravamo in background.
        val now = StorageAccess.hasRuntime(this)
        if (now != hadRuntimePermission) {
            hadRuntimePermission = now
            controller.onVolumesChanged()
        }
    }

    private fun bindViews() {
        list = findViewById(R.id.list)
        search = findViewById(R.id.search)
        recursive = findViewById(R.id.recursive)
        message = findViewById(R.id.message)
        empty = findViewById(R.id.empty)
        searching = findViewById(R.id.searching)
        statusDot = findViewById(R.id.status_dot)
        statusText = findViewById(R.id.status_text)
        statusAddress = findViewById(R.id.status_address)
        nowPlaying = findViewById(R.id.now_playing)
        btnPermissions = findViewById(R.id.btn_permissions)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnBackToPlayer = findViewById(R.id.btn_back_to_player)
        btnClosePlayer = findViewById(R.id.btn_close_player)
        playerContainer = findViewById(R.id.player_container)
        playerView = findViewById(R.id.player_view)
        miniHint = findViewById(R.id.mini_hint)
        resumePanel = findViewById(R.id.resume_panel)
        resumeMessage = findViewById(R.id.resume_message)
        resumeCountdown = findViewById(R.id.resume_countdown)
        resumeYes = findViewById(R.id.resume_yes)
        bindPlayerViews()

        adapter = EntryAdapter(
            focusable = true,
            onOpen = { index -> controller.submit(ClientMessage.Open(controller.listing.value.seq, index)) },
            onSelect = { index ->
                // Selezione dovuta al focus locale: la registro subito per non "rimbalzare" il
                // focus quando lo stato tornerà dal controller con lo stesso indice.
                lastSelected = index
                controller.submit(ClientMessage.Select(controller.listing.value.seq, index))
            },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        crumbBar = CrumbBar(findViewById<HorizontalScrollView>(R.id.crumbs_scroll), findViewById<LinearLayout>(R.id.crumbs)) { depth ->
            if (depth == 0) controller.submit(ClientMessage.Root) else controller.submit(ClientMessage.Crumb(depth))
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingRemote) return
                val text = s?.toString().orEmpty()
                lastSentQuery = text
                lastSentAt = SystemClock.elapsedRealtime()
                controller.submit(ClientMessage.Query(text))
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                // Chiudo la tastiera e porto il focus sui risultati, pronti per il D-pad.
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
                focusPosition(maxOf(0, adapter.positionOf(controller.state.value.selected)))
                true
            } else {
                false
            }
        }
        recursive.setOnCheckedChangeListener { _, checked ->
            if (!applyingRemote) controller.submit(ClientMessage.Recursive(checked))
        }

        findViewById<Button>(R.id.btn_root).setOnClickListener { controller.submit(ClientMessage.Root) }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { controller.submit(ClientMessage.Refresh) }
        findViewById<Button>(R.id.btn_add_saf).setOnClickListener { openTreePicker() }
        btnPermissions.setOnClickListener { requestAccess() }
        btnOverlay.setOnClickListener { requestOverlay() }
        findViewById<Button>(R.id.btn_export_logs).setOnClickListener { showExportLogs() }
        findViewById<Button>(R.id.btn_credits).setOnClickListener {
            CreditsDialog.show(this, R.string.credits_description_tv, R.string.credits_libraries_tv)
        }
        findViewById<Button>(R.id.btn_power).setOnClickListener {
            RemoteServerService.stop(this)
            finish()
        }
    }

    private fun bindPlayerViews() {
        playerView.setShowPreviousButton(true)
        playerView.setShowNextButton(true)
        playerView.setShowRewindButton(true)
        playerView.setShowFastForwardButton(true)
        playerView.controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
        playerView.defaultArtwork = getDrawable(CommonR.drawable.ic_music)
        // La miniatura è un unico elemento selezionabile: OK la riporta a schermo intero.
        playerContainer.setOnClickListener { controller.submit(ClientMessage.Layout(TvView.FULLSCREEN)) }
        playerContainer.setOnFocusChangeListener { _, _ -> renderMiniHint(playback.state.value) }
        btnBackToPlayer.setOnClickListener { controller.submit(ClientMessage.Layout(TvView.FULLSCREEN)) }
        btnClosePlayer.setOnClickListener { controller.submit(ClientMessage.Player(PlayerAction.STOP)) }
        resumeYes.setOnClickListener { controller.submit(ClientMessage.Resume(fromSaved = true)) }
        findViewById<Button>(R.id.resume_no).setOnClickListener { controller.submit(ClientMessage.Resume(fromSaved = false)) }
    }

    // ------------------------------------------------------------------ player

    /**
     * Collega [player] alla vista e vi registra [controllerTimeoutFixer], togliendolo dal player
     * precedente: il listener deve seguire sempre e solo il player mostrato.
     */
    private fun attachPlayer(player: Player?) {
        val previous = playerView.player
        if (previous === player) return
        previous?.removeListener(controllerTimeoutFixer)
        playerView.player = player
        player?.addListener(controllerTimeoutFixer)
    }

    /**
     * Media3 decide se i controlli restano visibili "per sempre" nel momento in cui li mostra:
     * se in quell'istante il player non sta ancora riproducendo (preparazione, buffering iniziale,
     * vista appena collegata) il timeout diventa 0. Lo ricalcola solo a fronte di alcuni eventi e
     * soltanto se i controlli sono già del tutto visibili (non durante l'animazione d'ingresso):
     * così, aprendo un video a schermo intero, i controlli restavano sopra l'immagine finché non
     * si premeva un tasto. All'avvio effettivo della riproduzione li rimostro io: showController()
     * rivaluta la condizione (ora falsa), reimposta il timeout normale e riavvia il conto alla
     * rovescia per nasconderli. Se l'avvio cade durante l'animazione d'ingresso dei controlli
     * ripeto il controllo poco dopo, a animazione conclusa.
     */
    private val controllerTimeoutFixer = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.removeCallbacks(refreshControllerTimeout)
            if (!isPlaying) return
            refreshControllerTimeout.run()
            playerView.postDelayed(refreshControllerTimeout, CONTROLLER_RECHECK_MS)
        }
    }

    private val refreshControllerTimeout = Runnable {
        val player = playerView.player
        if (player != null && player.isPlaying && playerView.useController && playerView.isControllerFullyVisible) {
            playerView.showController()
        }
    }

    /**
     * Applica lo stato del player: contenitore nascosto, a tutto schermo o in miniatura, e la
     * domanda di ripresa. Il layout cambia solo al cambio di disposizione; gli aggiornamenti al
     * secondo toccano soltanto i testi.
     */
    private fun renderPlayback(state: PlaybackState) {
        applyKeepScreenOn(state)
        btnBackToPlayer.visibility = if (state.active && state.view == TvView.MINI) View.VISIBLE else View.GONE
        btnClosePlayer.visibility = if (state.active) View.VISIBLE else View.GONE

        val layoutChanged = state.active != appliedActive || (state.active && state.view != appliedView)
        if (layoutChanged) {
            appliedActive = state.active
            appliedView = if (state.active) state.view else null
            applyPlayerLayout(state)
        }
        renderResume(state, layoutChanged)
        renderMiniHint(state)
    }

    private fun applyPlayerLayout(state: PlaybackState) {
        if (!state.active) {
            playerContainer.visibility = View.GONE
            playerView.hideController()
            // Il player si è chiuso: riporto il focus sull'elenco, salvo che si stia digitando.
            if (!search.hasFocus()) focusSelectedRow()
            return
        }
        playerContainer.visibility = View.VISIBLE
        val params = playerContainer.layoutParams as FrameLayout.LayoutParams
        if (state.view == TvView.FULLSCREEN) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.NO_GRAVITY
            params.setMargins(0, 0, 0, 0)
            playerContainer.layoutParams = params
            playerContainer.isFocusable = false
            playerView.useController = !state.resumePending
            if (!state.resumePending) {
                playerView.requestFocus()
                playerView.showController()
            }
        } else {
            // Miniatura 16:9 in basso a destra, entro il margine di sicurezza per l'overscan.
            params.width = dp(MINI_WIDTH_DP)
            params.height = dp(MINI_WIDTH_DP * 9 / 16)
            params.gravity = Gravity.BOTTOM or Gravity.END
            params.setMargins(0, 0, dp(48), dp(27))
            playerContainer.layoutParams = params
            playerView.hideController()
            playerView.useController = false
            playerContainer.isFocusable = true
            // Il file manager torna protagonista: focus sulla voce selezionata.
            if (!state.resumePending) focusSelectedRow()
        }
    }

    /**
     * Salvaschermo e standby per inattività: la TV li avvia se nessuno tocca il telecomando per
     * un certo tempo, e non considera "attività" un video che scorre. Il salvaschermo copre
     * l'activity, che va in onStop → il player si mette in pausa (vedi [PlaybackController.onUiStopped]).
     * FLAG_KEEP_SCREEN_ON sulla finestra è il segnale standard con cui un'app dice al sistema
     * "sto mostrando qualcosa, non spegnere né oscurare": lo tengo finché il player è attivo e
     * l'utente non l'ha messo in pausa (buffering e domanda di ripresa compresi, che durano
     * poco e precedono la riproduzione). In pausa lo tolgo: lasciare la TV accesa per ore su un
     * fermo immagine sarebbe uno spreco e un rischio per i pannelli OLED.
     */
    private fun applyKeepScreenOn(state: PlaybackState) {
        val keep = state.active && (state.playing || state.buffering || state.resumePending)
        if (keep == keepingScreenOn) return
        keepingScreenOn = keep
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        Log.i(TAG, "Schermo sempre acceso: $keep")
    }

    private fun renderResume(state: PlaybackState, layoutChanged: Boolean) {
        val pending = state.resumePending
        if (pending != appliedResume) {
            appliedResume = pending
            resumePanel.visibility = if (pending) View.VISIBLE else View.GONE
            if (pending) {
                resumeShownAt = SystemClock.elapsedRealtime()
                // I controlli del player catturerebbero il D-pad: li sospendo durante la domanda.
                playerView.hideController()
                playerView.useController = false
                resumeYes.requestFocus()
            } else if (state.active && state.view == TvView.FULLSCREEN) {
                playerView.useController = true
                playerView.requestFocus()
            } else if (!layoutChanged && state.active) {
                focusSelectedRow()
            }
        }
        if (!pending) return
        val title = state.title.orEmpty()
        val at = TimeFormat.format(state.resumeFromMs)
        resumeMessage.text = getString(CommonR.string.resume_message, title, at)
        resumeYes.text = getString(CommonR.string.resume_from, at)
        val left = (PlaybackController.RESUME_TIMEOUT_MS - (SystemClock.elapsedRealtime() - resumeShownAt)).coerceAtLeast(0)
        resumeCountdown.text = getString(R.string.resume_auto, ((left + 999) / 1000).toInt())
    }

    private fun renderMiniHint(state: PlaybackState) {
        val show = state.active && state.view == TvView.MINI && playerContainer.hasFocus()
        miniHint.visibility = if (show) View.VISIBLE else View.GONE
        if (show) miniHint.text = "${state.title.orEmpty()} · ${getString(R.string.mini_hint)}"
    }

    private fun focusSelectedRow() {
        val position = adapter.positionOf(controller.state.value.selected)
        if (position >= 0) focusPosition(position) else list.requestFocus()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    // ------------------------------------------------------------------ rendering

    private fun render(listing: Listing, state: RemoteState) {
        val visible = SearchText.visibleIndices(listing.entries, state.query)
        val structural = listing.seq != lastSeq
        // Se il focus era nell'elenco (o in nessun punto), dopo un cambio di elenco lo riporto
        // sulla voce selezionata: notifyDataSetChanged altrimenti lo perderebbe.
        val focusInList = list.hasFocus() || currentFocus == null
        adapter.submit(listing, visible, state.selected)
        lastSeq = listing.seq

        crumbBar.render(listing.crumbs, if (listing.searchResults) "🔍 ${state.query}" else null)
        syncSearchField(state)
        if (recursive.isChecked != state.recursive) {
            applyingRemote = true
            recursive.isChecked = state.recursive
            applyingRemote = false
        }

        message.text = state.message
        message.visibility = if (state.message.isNullOrBlank()) View.GONE else View.VISIBLE
        searching.visibility = if (state.searching) View.VISIBLE else View.GONE
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            state.searching -> getString(CommonR.string.searching)
            state.query.isNotBlank() -> getString(CommonR.string.no_results, state.query)
            else -> getString(CommonR.string.empty_folder)
        }
        nowPlaying.visibility = if (state.nowPlaying == null) View.GONE else View.VISIBLE
        nowPlaying.text = state.nowPlaying?.let { getString(CommonR.string.now_playing, it) }

        val position = adapter.positionOf(state.selected)
        if (structural) {
            lastSelected = state.selected
            if (focusInList && position >= 0) focusPosition(position)
        } else if (state.selected != lastSelected) {
            // Selezione cambiata dal telefono: sposto il focus se l'utente sta navigando
            // l'elenco, altrimenti mi limito a scorrere (non rubo il focus al campo di ricerca).
            lastSelected = state.selected
            if (position >= 0) {
                if (list.hasFocus()) focusPosition(position) else list.scrollToPosition(position)
            }
        }
    }

    private fun syncSearchField(state: RemoteState) {
        val current = search.text.toString()
        if (current == state.query) {
            lastSentQuery = null
            return
        }
        val pending = lastSentQuery
        val recentlyTyped = SystemClock.elapsedRealtime() - lastSentAt < ECHO_WINDOW_MS
        // Eco di una query intermedia mentre si digita: la ignoro, arriverà quella finale.
        if (pending != null && pending != state.query && recentlyTyped) return
        applyingRemote = true
        search.setText(state.query)
        search.setSelection(search.text.length)
        applyingRemote = false
        lastSentQuery = null
    }

    private fun focusPosition(position: Int) {
        list.scrollToPosition(position)
        list.post {
            val holder = list.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                // Riga non ancora disposta dopo lo scroll: riprovo al prossimo passaggio di layout.
                list.post { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
            }
        }
    }

    private fun renderStatus(info: TvApp.ServerInfo) {
        val color = when {
            !info.running -> getColor(CommonR.color.error)
            info.clients > 0 -> getColor(CommonR.color.ok)
            else -> getColor(CommonR.color.accent)
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        statusText.text = when {
            !info.running -> getString(R.string.status_server_off)
            info.clients > 0 -> resources.getQuantityString(R.plurals.status_clients, info.clients, info.clients)
            else -> getString(R.string.status_waiting)
        }
        statusAddress.text = if (info.address != null && info.running) {
            getString(R.string.status_address, info.address, info.port)
        } else {
            getString(R.string.status_no_network)
        }
    }

    private fun refreshPanelButtons() {
        btnPermissions.visibility = if (StorageAccess.hasFullAccess(this)) View.GONE else View.VISIBLE
        btnOverlay.visibility = if (StorageAccess.needsOverlay(this)) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ permessi

    private fun ensurePermissions() {
        if (!StorageAccess.hasRuntime(this)) {
            permissionLauncher.launch(StorageAccess.runtimePermissions())
        } else {
            offerFullAccessOnce()
        }
    }

    /** Propongo l'accesso completo una sola volta: poi resta il pulsante nel pannello. */
    private fun offerFullAccessOnce() {
        if (StorageAccess.hasFullAccess(this)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FULL_ACCESS_ASKED, false)) return
        prefs.edit().putBoolean(KEY_FULL_ACCESS_ASKED, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_full_title)
            .setMessage(R.string.perm_full_message)
            .setPositiveButton(R.string.perm_open_settings) { _, _ -> openFullAccessSettings() }
            .setNegativeButton(R.string.perm_not_now, null)
            .show()
    }

    private fun requestAccess() {
        if (!StorageAccess.hasRuntime(this)) {
            permissionLauncher.launch(StorageAccess.runtimePermissions())
        } else {
            openFullAccessSettings()
        }
    }

    private fun openFullAccessSettings() {
        for (intent in StorageAccess.fullAccessIntents(this)) {
            try {
                settingsLauncher.launch(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Schermata assente su questa TV: provo la successiva.
            }
        }
        showAdbHint("MANAGE_EXTERNAL_STORAGE")
    }

    private fun requestOverlay() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_message)
            .setPositiveButton(R.string.perm_open_settings) { _, _ ->
                try {
                    settingsLauncher.launch(StorageAccess.overlayIntent(this))
                } catch (_: ActivityNotFoundException) {
                    showAdbHint("SYSTEM_ALERT_WINDOW")
                }
            }
            .setNegativeButton(R.string.perm_not_now, null)
            .show()
    }

    private fun showAdbHint(operation: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_adb_title)
            .setMessage(getString(R.string.perm_adb_message, packageName, operation))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ esportazione dei log

    /**
     * Destinazioni dei log: Download (sempre disponibile), la cartella aperta nel file manager
     * se scrivibile (tipicamente il disco USB, comodo da portare al PC) e la condivisione verso
     * un'altra app. L'ultima voce ricorda che il telefono può scaricarli via rete.
     */
    private fun showExportLogs() {
        if (exportingLogs) return
        val folder = app.logExporter.writableCurrentFolder()
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels += getString(R.string.logs_to_downloads)
        actions += { exportLogs { getString(R.string.logs_saved, app.logExporter.saveToDownloads()) } }
        if (folder != null) {
            labels += getString(R.string.logs_to_folder, folder.name)
            actions += { exportLogs { getString(R.string.logs_saved, app.logExporter.saveToFolder(folder).absolutePath) } }
        }
        labels += getString(R.string.logs_share)
        actions += { shareLogs() }
        labels += getString(R.string.logs_from_phone)
        actions += {}
        AlertDialog.Builder(this)
            .setTitle(R.string.logs_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.perm_not_now, null)
            .show()
    }

    /** Esegue [work] (I/O e logcat) fuori dal main thread e ne mostra l'esito. */
    private fun exportLogs(work: () -> String) {
        exportingLogs = true
        toast(getString(R.string.logs_working))
        lifecycleScope.launch {
            val message = try {
                withContext(Dispatchers.IO) { work() }
            } catch (e: Exception) {
                Log.e(TAG, "Esportazione dei log non riuscita", e)
                getString(R.string.logs_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                exportingLogs = false
            }
            toast(message)
        }
    }

    private fun shareLogs() {
        exportingLogs = true
        toast(getString(R.string.logs_working))
        lifecycleScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { app.logExporter.shareIntent() }
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                toast(getString(R.string.logs_no_share))
            } catch (e: Exception) {
                Log.e(TAG, "Condivisione dei log non riuscita", e)
                toast(getString(R.string.logs_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                exportingLogs = false
            }
        }
    }

    private fun openTreePicker() {
        try {
            treeLauncher.launch(null)
        } catch (_: ActivityNotFoundException) {
            // Molte TV non includono il selettore documenti di sistema (DocumentsUI).
            toast(getString(R.string.saf_unavailable))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "TvMainActivity"
        const val PREFS = "tv_ui"
        const val KEY_FULL_ACCESS_ASKED = "full_access_asked"
        const val ECHO_WINDOW_MS = 1_500L
        const val CONTROLLER_TIMEOUT_MS = 4_000

        /** Oltre la durata dell'animazione d'ingresso dei controlli Media3 (~250 ms). */
        const val CONTROLLER_RECHECK_MS = 600L
        const val MINI_WIDTH_DP = 400

        /** Tasti del pad del telefono → tasti equivalenti del telecomando della TV. */
        val REMOTE_KEY_CODES = mapOf(
            RemoteKey.UP to KeyEvent.KEYCODE_DPAD_UP,
            RemoteKey.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            RemoteKey.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            RemoteKey.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            RemoteKey.OK to KeyEvent.KEYCODE_DPAD_CENTER,
            RemoteKey.BACK to KeyEvent.KEYCODE_BACK,
        )

        /** Tasti multimediali del telecomando → comandi del player. */
        val MEDIA_KEYS = mapOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to PlayerAction.PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY to PlayerAction.PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE to PlayerAction.PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP to PlayerAction.STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT to PlayerAction.NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to PlayerAction.PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to PlayerAction.FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND to PlayerAction.REWIND,
        )
    }
}
