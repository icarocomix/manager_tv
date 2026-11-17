package com.icarocomix.icaromanagertv.mobile.ui

import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.content.res.ColorStateList
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.icarocomix.icaromanagertv.common.log.AppLogs
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
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
import com.icarocomix.icaromanagertv.mobile.R
import com.icarocomix.icaromanagertv.mobile.client.Connection
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.icarocomix.icaromanagertv.common.R as CommonR

/**
 * Telecomando: specchio dello stato della TV. Il filtro di ricerca viene applicato subito anche
 * in locale (stessa regola della TV, [SearchText]), così l'elenco reagisce a ogni lettera senza
 * attendere il giro di rete; la TV riceve la query e mostra lo stesso risultato.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Scelta del file di destinazione dei log (Download, Drive, …) con il selettore di sistema. */
    private val createLogsDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            toast(getString(R.string.logs_working))
            viewModel.exportLogs(uri)
        }
    }

    private lateinit var toolbar: Toolbar
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerProgress: ProgressBar
    private lateinit var search: EditText
    private lateinit var searchClear: ImageButton
    private lateinit var recursive: CheckBox
    private lateinit var searching: ProgressBar
    private lateinit var count: TextView
    private lateinit var message: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var pad: View
    private lateinit var adapter: EntryAdapter
    private lateinit var crumbBar: CrumbBar

    // Schermate e controlli del player.
    private lateinit var browserScreen: View
    private lateinit var playerScreen: View
    private lateinit var nowBar: View
    private lateinit var nowBarIcon: ImageView
    private lateinit var nowBarTitle: TextView
    private lateinit var nowBarProgress: ProgressBar
    private lateinit var nowBarPlayPause: ImageButton
    private lateinit var playerIcon: ImageView
    private lateinit var playerTitle: TextView
    private lateinit var playerIndex: TextView
    private lateinit var playerBuffering: ProgressBar
    private lateinit var playerSeek: SeekBar
    private lateinit var playerElapsed: TextView
    private lateinit var playerDuration: TextView
    private lateinit var playerPlayPause: ImageButton
    private lateinit var playerPrevious: ImageButton
    private lateinit var playerNext: ImageButton
    private lateinit var playerAutoplay: SwitchCompat
    private lateinit var playerTvLayout: Button
    private lateinit var playerVolumeSeek: SeekBar
    private lateinit var playerVolumeSpacer: View
    private lateinit var playerVolumeValue: TextView
    private lateinit var playerVolumeMute: ImageButton

    /** Schermata mostrata: solo locale, cambiarla non tocca la riproduzione sulla TV. */
    private var showingPlayer = false
    private var wasPlaybackActive = false
    private var userSeeking = false

    // Cursore del volume: durante il trascinamento (e per un attimo dopo) ignoro il livello
    // pubblicato dalla TV, che arriva in ritardo e farebbe saltare indietro il cursore.
    private var userDraggingVolume = false
    private var volumeHoldUntil = 0L
    private var lastVolumeSentAt = 0L
    private var lastVolumeKeyAt = 0L
    private var resumeDialog: AlertDialog? = null
    private var resumeDialogKey: String? = null

    // Echo della query: vedi syncSearchField.
    private var applyingRemote = false
    private var lastSentQuery: String? = null
    private var lastSentAt = 0L
    private var lastSelected = -1
    private var lastSeq = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Log del telefono su file (idempotente): servono nel pacchetto esportato insieme a quelli della TV.
        AppLogs.start(this)
        setContentView(R.layout.activity_main)
        applyWindowInsets()
        bindViews()
        bindPlayerViews()
        showingPlayer = savedInstanceState?.getBoolean(KEY_SHOWING_PLAYER) ?: false
        // Dopo una rotazione non devo trattare la riproduzione già in corso come "appena iniziata".
        wasPlaybackActive = viewModel.client.playback.value.active
        applyScreen()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.client.connection.collect(::renderConnection) }
                launch {
                    combine(viewModel.client.listing, viewModel.client.state, ::Pair).collect { (l, s) ->
                        if (l != null && s != null) render(l, s)
                    }
                }
                // L'adattatore usa l'avanzamento solo se riferito all'elenco mostrato (stessa seq).
                launch { viewModel.client.progress.collect { adapter.submitProgress(it) } }
                launch { viewModel.client.toasts.collect { toast(it) } }
                launch { viewModel.messages.collect { toast(it) } }
                launch {
                    viewModel.exportingLogs.collect { busy -> toolbar.menu.findItem(R.id.action_export_logs)?.isEnabled = !busy }
                }
                launch { viewModel.client.playback.collect(::renderPlayback) }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            // Dalla schermata del player "Indietro" torna al file manager: la TV continua.
            if (showingPlayer) {
                showBrowser()
                return@addCallback
            }
            // Indietro risale le cartelle della TV; alla radice (senza ricerca) esce dall'app.
            val listing = viewModel.client.listing.value
            val state = viewModel.client.state.value
            val canGoBack = viewModel.client.connection.value is Connection.Connected &&
                (listing?.crumbs?.isNotEmpty() == true || state?.query?.isNotEmpty() == true)
            if (canGoBack) send(ClientMessage.Key(RemoteKey.BACK)) else finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOWING_PLAYER, showingPlayer)
    }

    override fun onDestroy() {
        resumeDialog?.dismiss()
        resumeDialog = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    /** Da Android 15 la finestra è sempre edge-to-edge: lascio spazio a barre di sistema e tastiera. */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.title = getString(R.string.toolbar_title)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_choose_tv -> showTvChooser()
                R.id.action_refresh -> send(ClientMessage.Refresh)
                R.id.action_export_logs -> createLogsDocument.launch(AppLogs.suggestedFileName("tv-telefono"))
                R.id.action_credits -> CreditsDialog.show(this, R.string.credits_description_mobile, R.string.credits_libraries_mobile)
            }
            true
        }
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.banner_text)
        bannerProgress = findViewById(R.id.banner_progress)
        banner.setOnClickListener { showTvChooser() }
        search = findViewById(R.id.search)
        searchClear = findViewById(R.id.search_clear)
        recursive = findViewById(R.id.recursive)
        searching = findViewById(R.id.searching)
        count = findViewById(R.id.count)
        message = findViewById(R.id.message)
        nowPlaying = findViewById(R.id.now_playing)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        pad = findViewById(R.id.pad)

        adapter = EntryAdapter(
            focusable = false,
            onOpen = { index ->
                val seq = viewModel.client.listing.value?.seq ?: return@EntryAdapter
                send(ClientMessage.Open(seq, index))
            },
            onSelect = { index ->
                val seq = viewModel.client.listing.value?.seq ?: return@EntryAdapter
                list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                send(ClientMessage.Select(seq, index))
            },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        crumbBar = CrumbBar(findViewById<HorizontalScrollView>(R.id.crumbs_scroll), findViewById<LinearLayout>(R.id.crumbs)) { depth ->
            send(if (depth == 0) ClientMessage.Root else ClientMessage.Crumb(depth))
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                searchClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                if (applyingRemote) return
                lastSentQuery = text
                lastSentAt = SystemClock.elapsedRealtime()
                send(ClientMessage.Query(text), silent = true)
                // Filtro subito in locale, senza attendere l'eco della TV.
                renderCurrent()
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
                true
            } else {
                false
            }
        }
        searchClear.setOnClickListener { search.setText("") }
        recursive.setOnCheckedChangeListener { _, checked ->
            if (!applyingRemote) send(ClientMessage.Recursive(checked))
        }

        findViewById<Button>(R.id.cmd_root).setOnClickListener { send(ClientMessage.Root) }
        findViewById<Button>(R.id.cmd_up).setOnClickListener { send(ClientMessage.Up) }
        findViewById<Button>(R.id.cmd_refresh).setOnClickListener { send(ClientMessage.Refresh) }
        findViewById<Button>(R.id.cmd_pad).setOnClickListener {
            pad.visibility = if (pad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        bindPad(pad)
    }

    private fun bindPlayerViews() {
        browserScreen = findViewById(R.id.browser_screen)
        playerScreen = findViewById(R.id.player_screen)
        nowBar = findViewById(R.id.now_bar)
        nowBarIcon = findViewById(R.id.now_bar_icon)
        nowBarTitle = findViewById(R.id.now_bar_title)
        nowBarProgress = findViewById(R.id.now_bar_progress)
        nowBarPlayPause = findViewById(R.id.now_bar_play_pause)
        playerIcon = findViewById(R.id.player_icon)
        playerTitle = findViewById(R.id.player_title)
        playerIndex = findViewById(R.id.player_index)
        playerBuffering = findViewById(R.id.player_buffering)
        playerSeek = findViewById(R.id.player_seek)
        playerElapsed = findViewById(R.id.player_elapsed)
        playerDuration = findViewById(R.id.player_duration)
        playerPlayPause = findViewById(R.id.player_play_pause)
        playerPrevious = findViewById(R.id.player_previous)
        playerNext = findViewById(R.id.player_next)
        playerAutoplay = findViewById(R.id.player_autoplay)
        playerTvLayout = findViewById(R.id.player_tv_layout)
        playerVolumeSeek = findViewById(R.id.player_volume_seek)
        playerVolumeSpacer = findViewById(R.id.player_volume_spacer)
        playerVolumeValue = findViewById(R.id.player_volume_value)
        playerVolumeMute = findViewById(R.id.player_volume_mute)

        // Navigazione tra le schermate del telefono: nessun messaggio alla TV.
        nowBar.setOnClickListener { showPlayer() }
        findViewById<ImageButton>(R.id.now_bar_open).setOnClickListener { showPlayer() }
        findViewById<Button>(R.id.player_back).setOnClickListener { showBrowser() }

        // Pad anche sulla schermata del player: sulla TV a schermo intero i tasti muovono il
        // focus tra i controlli del player, come il telecomando della TV.
        val playerPad = findViewById<View>(R.id.player_pad)
        bindPad(playerPad)
        findViewById<Button>(R.id.player_pad_toggle).setOnClickListener {
            playerPad.visibility = if (playerPad.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        nowBarPlayPause.setOnClickListener { playerCommand(it, PlayerAction.PLAY_PAUSE) }
        playerPlayPause.setOnClickListener { playerCommand(it, PlayerAction.PLAY_PAUSE) }
        playerPrevious.setOnClickListener { playerCommand(it, PlayerAction.PREVIOUS) }
        playerNext.setOnClickListener { playerCommand(it, PlayerAction.NEXT) }
        findViewById<ImageButton>(R.id.player_rewind).setOnClickListener { playerCommand(it, PlayerAction.REWIND) }
        findViewById<ImageButton>(R.id.player_forward).setOnClickListener { playerCommand(it, PlayerAction.FORWARD) }
        findViewById<Button>(R.id.player_start).setOnClickListener { playerCommand(it, PlayerAction.SEEK_START) }
        findViewById<Button>(R.id.player_end).setOnClickListener { playerCommand(it, PlayerAction.SEEK_END) }
        findViewById<Button>(R.id.player_stop).setOnClickListener { playerCommand(it, PlayerAction.STOP) }
        findViewById<ImageButton>(R.id.player_volume_up).setOnClickListener { playerCommand(it, PlayerAction.VOLUME_UP) }
        findViewById<ImageButton>(R.id.player_volume_down).setOnClickListener { playerCommand(it, PlayerAction.VOLUME_DOWN) }
        playerVolumeMute.setOnClickListener { playerCommand(it, PlayerAction.MUTE) }
        bindVolumeSeek()

        playerAutoplay.setOnCheckedChangeListener { _, checked ->
            if (!applyingRemote) send(ClientMessage.Autoplay(checked))
        }
        playerTvLayout.setOnClickListener {
            val current = viewModel.client.playback.value.view
            send(ClientMessage.Layout(if (current == TvView.FULLSCREEN) TvView.MINI else TvView.FULLSCREEN))
        }

        playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                // Durante il trascinamento mostro il punto di arrivo, non la posizione della TV.
                if (fromUser) playerElapsed.text = TimeFormat.format(progress * 1000L)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                // Un solo comando al rilascio: niente raffica di salti mentre il dito scorre.
                send(ClientMessage.SeekTo(bar.progress * 1000L))
            }
        })
    }

    private fun bindVolumeSeek() {
        playerVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                showVolumeValue(progress, bar.max, muted = false)
                // Invio anche durante il trascinamento, ma diradato: la TV segue il dito senza
                // ricevere una raffica di comandi a ogni pixel.
                val now = SystemClock.elapsedRealtime()
                if (now - lastVolumeSentAt >= VOLUME_SEND_INTERVAL_MS) {
                    lastVolumeSentAt = now
                    send(ClientMessage.Volume(progress), silent = true)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userDraggingVolume = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userDraggingVolume = false
                volumeHoldUntil = SystemClock.elapsedRealtime() + VOLUME_HOLD_MS
                // Livello finale sempre inviato: l'ultimo passo potrebbe essere stato diradato.
                send(ClientMessage.Volume(bar.progress))
            }
        })
    }

    private fun renderVolume(state: PlaybackState) {
        // Stato del muto evidenziato nel colore del tasto: l'icona resta quella dell'azione.
        val tint = ContextCompat.getColor(this, if (state.muted) CommonR.color.accent else CommonR.color.text_primary)
        playerVolumeMute.imageTintList = ColorStateList.valueOf(tint)
        playerVolumeMute.isActivated = state.muted

        val adjustable = state.volumeAdjustable
        playerVolumeSeek.visibility = if (adjustable) View.VISIBLE else View.GONE
        playerVolumeSpacer.visibility = if (adjustable) View.GONE else View.VISIBLE
        if (!adjustable) {
            // Volume fisso o TV precedente alla v1.4: solo tasti, nessun livello da mostrare.
            playerVolumeValue.text = if (state.muted) getString(CommonR.string.player_volume_muted) else ""
            return
        }
        if (userDraggingVolume || SystemClock.elapsedRealtime() < volumeHoldUntil) return
        playerVolumeSeek.max = state.volumeMax
        playerVolumeSeek.progress = state.volume
        showVolumeValue(state.volume, state.volumeMax, state.muted)
    }

    private fun showVolumeValue(level: Int, max: Int, muted: Boolean) {
        playerVolumeValue.text = when {
            muted -> getString(CommonR.string.player_volume_muted)
            max > 0 -> getString(CommonR.string.player_volume_level, level * 100 / max)
            else -> ""
        }
    }

    /**
     * Con la schermata del player aperta i tasti volume del telefono comandano la TV: è il
     * comportamento atteso da un telecomando. Se il comando non parte (non collegato) lascio
     * l'evento al sistema, che regola il volume del telefono come sempre.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> PlayerAction.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> PlayerAction.VOLUME_DOWN
            KeyEvent.KEYCODE_VOLUME_MUTE -> PlayerAction.MUTE
            else -> null
        }
        if (action == null || !showingPlayer || viewModel.client.connection.value !is Connection.Connected) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Tasto tenuto premuto: l'autorepeat del sistema è troppo rapido per la TV (ogni
            // evento è un passo), lo dirado. Il muto non si ripete.
            val now = SystemClock.elapsedRealtime()
            val repeatAllowed = action != PlayerAction.MUTE && now - lastVolumeKeyAt >= VOLUME_KEY_REPEAT_MS
            if (event.repeatCount == 0 || repeatAllowed) {
                lastVolumeKeyAt = now
                if (!viewModel.send(ClientMessage.Player(action))) return super.dispatchKeyEvent(event)
            }
        }
        return true
    }

    private fun playerCommand(view: View, action: PlayerAction) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        send(ClientMessage.Player(action))
    }

    private fun showPlayer() {
        if (!viewModel.client.playback.value.active) return
        showingPlayer = true
        applyScreen()
    }

    private fun showBrowser() {
        showingPlayer = false
        applyScreen()
    }

    private fun applyScreen() {
        val active = viewModel.client.playback.value.active
        if (!active) showingPlayer = false
        playerScreen.visibility = if (showingPlayer) View.VISIBLE else View.GONE
        browserScreen.visibility = if (showingPlayer) View.GONE else View.VISIBLE
        nowBar.visibility = if (active && !showingPlayer) View.VISIBLE else View.GONE
        toolbar.title = getString(if (showingPlayer) CommonR.string.now_playing_short else R.string.toolbar_title)
        if (showingPlayer) {
            // La tastiera della ricerca non serve sulla schermata del player.
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        }
    }

    // ------------------------------------------------------------------ player

    private fun renderPlayback(state: PlaybackState) {
        // Riproduzione appena partita (da qui o dalla TV): porto in primo piano il telecomando
        // del player. Il passaggio automatico al file successivo non cambia schermata.
        if (state.active && !wasPlaybackActive) showingPlayer = true
        wasPlaybackActive = state.active
        applyScreen()
        renderResumeDialog(state)
        if (!state.active) return

        val icon = if (state.kind == EntryKind.AUDIO) CommonR.drawable.ic_music else CommonR.drawable.ic_movie
        nowBarIcon.setImageResource(icon)
        playerIcon.setImageResource(icon)
        val title = state.title.orEmpty()
        nowBarTitle.text = title
        playerTitle.text = title
        playerIndex.text = if (state.count > 1) getString(CommonR.string.playlist_position, state.index + 1, state.count) else ""
        playerBuffering.visibility = if (state.buffering) View.VISIBLE else View.GONE

        val playPauseIcon = if (state.playing) CommonR.drawable.ic_pause else CommonR.drawable.ic_play
        nowBarPlayPause.setImageResource(playPauseIcon)
        playerPlayPause.setImageResource(playPauseIcon)
        setEnabledLook(playerPrevious, state.hasPrevious)
        setEnabledLook(playerNext, state.hasNext)

        val duration = state.durationMs
        nowBarProgress.progress = if (duration > 0) ((state.positionMs * 1000) / duration).toInt() else 0
        playerSeek.isEnabled = duration > 0
        if (!userSeeking) {
            playerSeek.max = if (duration > 0) (duration / 1000).toInt() else 0
            playerSeek.progress = (state.positionMs / 1000).toInt()
            playerElapsed.text = TimeFormat.format(state.positionMs)
        }
        playerDuration.text = TimeFormat.format(duration)

        if (playerAutoplay.isChecked != state.autoplay) {
            applyingRemote = true
            playerAutoplay.isChecked = state.autoplay
            applyingRemote = false
        }
        val full = state.view == TvView.FULLSCREEN
        renderVolume(state)
        playerTvLayout.setText(if (full) R.string.tv_layout_mini else R.string.tv_layout_full)
        playerTvLayout.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (full) CommonR.drawable.ic_pip else CommonR.drawable.ic_fullscreen, 0, 0, 0,
        )
    }

    private fun setEnabledLook(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    /**
     * Domanda di ripresa: la mostro finché la TV la tiene aperta. Chi risponde per primo (TV o
     * telefono) la chiude per entrambi; chiuderla senza scegliere lascia decidere la TV (che
     * riprende da sola dopo qualche secondo).
     */
    private fun renderResumeDialog(state: PlaybackState) {
        val key = if (state.resumePending) "${state.title}|${state.resumeFromMs}" else null
        if (key == resumeDialogKey) return
        resumeDialog?.dismiss()
        resumeDialog = null
        resumeDialogKey = key
        if (key == null) return
        val at = TimeFormat.format(state.resumeFromMs)
        resumeDialog = AlertDialog.Builder(this)
            .setTitle(CommonR.string.resume_title)
            .setMessage(getString(CommonR.string.resume_message, state.title.orEmpty(), at))
            .setPositiveButton(getString(CommonR.string.resume_from, at)) { _, _ -> send(ClientMessage.Resume(fromSaved = true)) }
            .setNegativeButton(CommonR.string.resume_restart) { _, _ -> send(ClientMessage.Resume(fromSaved = false)) }
            .show()
    }

    /**
     * Collega i tasti di un pad (layout view_pad). Il pad compare in più schermate con gli stessi
     * id, quindi cerco i tasti dentro il contenitore e non nell'intera activity.
     */
    private fun bindPad(container: View) {
        bindPadKey(container, R.id.pad_up, RemoteKey.UP)
        bindPadKey(container, R.id.pad_down, RemoteKey.DOWN)
        bindPadKey(container, R.id.pad_left, RemoteKey.LEFT)
        bindPadKey(container, R.id.pad_right, RemoteKey.RIGHT)
        bindPadKey(container, R.id.pad_ok, RemoteKey.OK)
        bindPadKey(container, R.id.pad_back, RemoteKey.BACK)
        container.findViewById<ImageButton>(R.id.pad_home).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            send(ClientMessage.Root)
        }
    }

    private fun bindPadKey(container: View, id: Int, key: RemoteKey) {
        container.findViewById<ImageButton>(id).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            send(ClientMessage.Key(key))
        }
    }

    private fun send(message: ClientMessage, silent: Boolean = false) {
        if (!viewModel.send(message) && !silent) toast(getString(R.string.not_connected))
    }

    // ------------------------------------------------------------------ rendering

    private fun renderConnection(connection: Connection) {
        val connected = connection is Connection.Connected
        banner.visibility = if (connected) View.GONE else View.VISIBLE
        bannerProgress.visibility = if (connection is Connection.Failed) View.GONE else View.VISIBLE
        bannerText.text = when (connection) {
            Connection.Searching -> getString(R.string.conn_searching)
            is Connection.Connecting -> getString(R.string.conn_connecting, connection.tv.name)
            is Connection.Retrying -> getString(R.string.conn_retrying, connection.tv.name, ((connection.delayMs + 999) / 1000).toInt())
            is Connection.Failed -> getString(R.string.conn_failed, connection.reason)
            is Connection.Connected -> ""
        }
        toolbar.subtitle = if (connection is Connection.Connected) getString(R.string.conn_connected, connection.serverName) else null
        // Elenco attenuato mentre non è collegato: mostra l'ultimo stato noto ma non è interattivo.
        list.alpha = if (connected) 1f else 0.45f
        list.isEnabled = connected
    }

    private fun renderCurrent() {
        val l = viewModel.client.listing.value ?: return
        val s = viewModel.client.state.value ?: return
        render(l, s)
    }

    private fun render(listing: Listing, state: RemoteState) {
        syncSearchField(state)
        // Filtro con il testo del campo (immediato), non con la query della TV (in ritardo di un giro).
        val query = search.text.toString()
        val visible = SearchText.visibleIndices(listing.entries, query)
        val structural = listing.seq != lastSeq
        adapter.submit(listing, visible, state.selected)
        lastSeq = listing.seq

        crumbBar.render(listing.crumbs, if (listing.searchResults) "🔍 ${state.query}" else null)
        if (recursive.isChecked != state.recursive) {
            applyingRemote = true
            recursive.isChecked = state.recursive
            applyingRemote = false
        }
        searching.visibility = if (state.searching) View.VISIBLE else View.GONE
        count.text = if (query.isNotBlank() || listing.searchResults) getString(CommonR.string.results_count, visible.size) else ""
        message.text = state.message
        message.visibility = if (state.message.isNullOrBlank()) View.GONE else View.VISIBLE
        nowPlaying.text = state.nowPlaying?.let { getString(CommonR.string.now_playing, it) }
        // Con il player integrato attivo la mini-barra mostra già il titolo: evito il doppione.
        val internal = viewModel.client.playback.value.active
        nowPlaying.visibility = if (state.nowPlaying == null || internal) View.GONE else View.VISIBLE
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            state.searching -> getString(CommonR.string.searching)
            query.isNotBlank() -> getString(CommonR.string.no_results, query)
            else -> getString(CommonR.string.empty_folder)
        }

        // Selezione mossa dalla TV o dal pad: la porto in vista se è fuori schermo.
        if (structural) {
            list.scrollToPosition(maxOf(0, adapter.positionOf(state.selected)))
        } else if (state.selected != lastSelected) {
            val pos = adapter.positionOf(state.selected)
            val lm = list.layoutManager as LinearLayoutManager
            if (pos >= 0 && (pos < lm.findFirstCompletelyVisibleItemPosition() || pos > lm.findLastCompletelyVisibleItemPosition())) {
                list.scrollToPosition(pos)
            }
        }
        lastSelected = state.selected
    }

    /**
     * Allinea il campo di ricerca alla query della TV (es. digitata con la tastiera della TV),
     * ignorando le eco intermedie di ciò che si sta digitando qui: altrimenti una risposta
     * a "ma" arrivata dopo aver digitato "mat" riporterebbe il testo indietro.
     */
    private fun syncSearchField(state: RemoteState) {
        val current = search.text.toString()
        if (current == state.query) {
            lastSentQuery = null
            return
        }
        val pending = lastSentQuery
        val recentlyTyped = SystemClock.elapsedRealtime() - lastSentAt < ECHO_WINDOW_MS
        if (pending != null && pending != state.query && recentlyTyped) return
        applyingRemote = true
        search.setText(state.query)
        search.setSelection(search.text.length)
        applyingRemote = false
        lastSentQuery = null
    }

    // ------------------------------------------------------------------ scelta della TV

    private fun showTvChooser() {
        val tvs = viewModel.client.discovery.endpoints.value.values.sortedBy { it.name.lowercase() }
        if (tvs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_tv_title)
                .setMessage(R.string.choose_tv_empty)
                .setPositiveButton(CommonR.string.credits_close, null)
                .show()
            viewModel.client.discovery.probeNow()
            return
        }
        val currentId = (viewModel.client.connection.value as? Connection.Connected)?.tv?.id
        val labels = tvs.map { tv ->
            val base = getString(R.string.tv_item, tv.name, tv.host)
            if (tv.id == currentId) getString(R.string.tv_current, base) else base
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_tv_title)
            .setItems(labels) { _, which -> viewModel.client.choose(tvs[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val ECHO_WINDOW_MS = 1_500L
        const val KEY_SHOWING_PLAYER = "showing_player"
        const val VOLUME_SEND_INTERVAL_MS = 150L
        const val VOLUME_HOLD_MS = 1_200L
        const val VOLUME_KEY_REPEAT_MS = 180L
    }
}
