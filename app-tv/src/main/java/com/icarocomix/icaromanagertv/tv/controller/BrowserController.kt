package com.icarocomix.icaromanagertv.tv.controller

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.icarocomix.icaromanagertv.common.files.FileNames
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import com.icarocomix.icaromanagertv.common.protocol.FileOpKind
import com.icarocomix.icaromanagertv.common.protocol.Listing
import com.icarocomix.icaromanagertv.common.protocol.ListingProgress
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import com.icarocomix.icaromanagertv.common.protocol.RemoteKey
import com.icarocomix.icaromanagertv.common.protocol.RemoteState
import com.icarocomix.icaromanagertv.common.protocol.TvStatus
import com.icarocomix.icaromanagertv.common.protocol.TvView
import com.icarocomix.icaromanagertv.common.protocol.UiCommand
import com.icarocomix.icaromanagertv.common.search.SearchText
import com.icarocomix.icaromanagertv.tv.R
import com.icarocomix.icaromanagertv.tv.playback.PlaybackController
import com.icarocomix.icaromanagertv.tv.playback.ProgressChange
import com.icarocomix.icaromanagertv.tv.playback.ProgressStore
import com.icarocomix.icaromanagertv.tv.storage.ClipItem
import com.icarocomix.icaromanagertv.tv.storage.FileOperationException
import com.icarocomix.icaromanagertv.tv.storage.FileOperations
import com.icarocomix.icaromanagertv.tv.storage.MediaTypes
import com.icarocomix.icaromanagertv.tv.storage.Node
import com.icarocomix.icaromanagertv.tv.storage.NodeRef
import com.icarocomix.icaromanagertv.tv.storage.SearchHit
import com.icarocomix.icaromanagertv.tv.storage.StorageAccessException
import com.icarocomix.icaromanagertv.tv.storage.StorageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Stato autorevole del file manager della TV. Telefono e interfaccia della TV inviano gli stessi
 * comandi ([ClientMessage]); il controller li applica uno alla volta, nell'ordine di arrivo, su
 * un'unica coroutine: nessun lock e nessuna corsa tra telefono e telecomando della TV.
 *
 * Dalla v1.9 la navigazione vive in due sessioni ([Session]): quella della TV, mostrata sullo
 * schermo, e quella del telefono. Con la sincronizzazione attiva (default) il telefono usa la
 * sessione della TV: ogni spostamento, dall'una o dall'altra parte, si vede su entrambi. Spenta,
 * il telefono riceve una copia indipendente e il telecomando della TV si muove per conto suo;
 * riaccendendola la TV adotta la posizione del telefono.
 *
 * Pubblica per la TV [listing]/[state]/[progress] (sessione TV), per il server
 * [remoteListing]/[remoteState]/[remoteProgress] (sessione del telefono, che con la
 * sincronizzazione coincide con quella della TV) e per entrambi [status] (sincronizzazione,
 * appunti, operazione in corso).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserController(
    private val context: Context,
    private val storage: StorageRepository,
    private val files: FileOperations,
    private val launcher: MediaLauncher,
    private val playback: PlaybackController,
    private val progressStore: ProgressStore,
    private val scope: CoroutineScope,
) {

    private sealed interface Command {
        data class Client(val message: ClientMessage, val fromPhone: Boolean) : Command
        data class SearchBatch(val session: Session, val token: Int, val hits: List<SearchHit>) : Command
        data class SearchDone(val session: Session, val token: Int) : Command
        data class VolumeRemoved(val mountPath: String?) : Command
        data object VolumesChanged : Command
        data class RefreshProgress(val session: Session, val listingSeq: Int) : Command
        data class ProgressChanged(val change: ProgressChange) : Command

        /** Fine di un'operazione sui file: esito da mostrare e voce da selezionare in [target]. */
        data class OpFinished(val target: Session, val message: String?, val selectName: String?, val clearClip: Boolean) : Command

        /** Contenuto della cartella [folder] cambiato da fuori del ciclo (caricamento dal telefono). */
        data class FolderChanged(val folder: Node, val selectName: String?) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** Preferenze del file manager che sopravvivono al riavvio (ricerca ricorsiva, sincronizzazione). */
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Sequenza unica tra le sessioni: un comando con la sequenza di un'altra sessione non combacia mai per caso. */
    private var seqCounter = 0

    private val tv = Session()

    /** Sessione separata del telefono, solo con la sincronizzazione spenta. */
    private var phone: Session? = null

    /** Sessione che il telefono vede e comanda: [tv] con la sincronizzazione attiva. */
    private val phoneSession = MutableStateFlow(tv)

    val listing: StateFlow<Listing> get() = tv.listing
    val state: StateFlow<RemoteState> get() = tv.state
    val progress: StateFlow<ListingProgress> get() = tv.progress

    // Il telefono segue la sessione corrente: al cambio di sessione riceve subito l'elenco nuovo.
    val remoteListing: StateFlow<Listing> =
        phoneSession.flatMapLatest { it.listing }.stateIn(scope, SharingStarted.Eagerly, Listing.EMPTY)
    val remoteState: StateFlow<RemoteState> =
        phoneSession.flatMapLatest { it.state }.stateIn(scope, SharingStarted.Eagerly, RemoteState())
    val remoteProgress: StateFlow<ListingProgress> =
        phoneSession.flatMapLatest { it.progress }.stateIn(scope, SharingStarted.Eagerly, ListingProgress.EMPTY)

    private val _status = MutableStateFlow(TvStatus(synced = prefs.getBoolean(KEY_SYNCED, true)))
    val status: StateFlow<TvStatus> = _status.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    /**
     * Vero quando una scrittura è fallita per un permesso di accesso ai file non concesso. Uso
     * uno StateFlow e non un evento: se l'operazione arriva dal telefono con la UI della TV non
     * visibile, la richiesta del permesso compare comunque alla prossima apertura dell'app.
     * La UI lo riporta a falso con [consumeAccessRequest] quando mostra la richiesta.
     */
    private val _accessRequest = MutableStateFlow(false)
    val accessRequest: StateFlow<Boolean> = _accessRequest.asStateFlow()

    fun reportAccessNeeded() {
        _accessRequest.value = true
    }

    fun consumeAccessRequest() {
        _accessRequest.value = false
    }

    // Appunti: letti e scritti solo dal ciclo dei comandi.
    private var clipboard: List<ClipItem> = emptyList()
    private var clipMove = false

    /**
     * Unica "corsia" per le scritture sul disco: operazioni del file manager e trasferimenti dal
     * o verso il telefono si escludono a vicenda, così non si incrociano due copie sulla stessa
     * chiavetta (lente e con esiti imprevedibili se toccano gli stessi file).
     */
    private val busy = AtomicBoolean(false)
    private var opJob: Job? = null

    /**
     * Elenchi pubblicati di recente (sequenza → cartella e voci), per gli endpoint HTTP di
     * caricamento e scaricamento: girano sui thread del server, quindi accesso sincronizzato.
     * Tenerne più d'uno permette di caricare una serie di file nella cartella scelta anche se
     * nel frattempo il telefono si è spostato altrove.
     */
    private val recentListings = object : LinkedHashMap<Int, ListingRef>(RECENT_LISTINGS + 1, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ListingRef>?): Boolean = size > RECENT_LISTINGS
    }

    private class ListingRef(val folder: Node?, val nodes: List<Node>)

    private var started = false

    /**
     * Cartella del disco aperta ora sulla TV, se è un percorso POSIX (null alla radice o in un
     * albero SAF). Scritta solo dal ciclo dei comandi, letta dalla UI per salvarvi i log.
     */
    @Volatile
    var currentFolder: java.io.File? = null
        private set

    /** Avvia il ciclo dei comandi e carica la radice. Idempotente. */
    fun start() {
        if (started) return
        started = true
        // Anche le notifiche dell'archivio passano dalla coda: mappe degli indici ed elenchi
        // restano di proprietà esclusiva del ciclo dei comandi.
        scope.launch {
            progressStore.changes.collect { change -> commands.trySend(Command.ProgressChanged(change)) }
        }
        scope.launch(Dispatchers.IO) {
            tv.loadCurrent()
            // Sincronizzazione lasciata spenta dall'utente: il telefono riparte da dove è la TV.
            if (!_status.value.synced) detachPhone()
            for (command in commands) {
                // Un errore imprevisto su un comando non deve fermare il ciclo: lo registro e
                // proseguo, altrimenti la TV smetterebbe di rispondere a tutti i client.
                try {
                    handle(command)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Comando non eseguito: $command", e)
                    toast(context.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Accoda un comando dell'interfaccia della TV; chiamabile da qualunque thread. */
    fun submit(message: ClientMessage) {
        commands.trySend(Command.Client(message, fromPhone = false))
    }

    /**
     * Interfaccia della TV in primo piano che vuole ricevere i tasti del pad del telefono come
     * se arrivassero dal telecomando fisico. Impostato dall'activity in onStart e tolto in onStop.
     * Volatile: scritto dal main thread, letto dai thread del server.
     */
    @Volatile
    var remoteKeyTarget: ((RemoteKey) -> Unit)? = null

    /**
     * Ingresso dei comandi provenienti dal telefono. I tasti del pad sono un telecomando della
     * TV, anche con la sincronizzazione spenta: con l'interfaccia della TV visibile li consegno a
     * lei, altrimenti agiscono sulla sessione della TV. Tutti gli altri comandi di navigazione
     * agiscono sulla sessione del telefono.
     */
    fun submitFromRemote(message: ClientMessage) {
        val target = remoteKeyTarget
        when {
            message is ClientMessage.Key && target != null -> target(message.key)
            message is ClientMessage.Key -> submit(message)
            else -> commands.trySend(Command.Client(message, fromPhone = true))
        }
    }

    /**
     * Interfaccia della TV visibile che riceve i comandi `ui` del telefono (apertura di
     * impostazioni e sottotitoli del player). Impostato dall'activity in onStart e tolto in onStop,
     * come [remoteKeyTarget].
     */
    @Volatile
    var remoteUiTarget: ((UiCommand) -> Unit)? = null

    /** Porta in primo piano l'interfaccia della TV quando non è visibile (impostato da TvApp). */
    @Volatile
    var uiLauncher: (() -> Unit)? = null

    /** Comando di un pannello del player arrivato con l'interfaccia chiusa, e quando è arrivato. */
    private val pendingUi = AtomicReference<Pair<UiCommand, Long>?>(null)

    /**
     * Comando del telefono rimasto in attesa dell'apertura dell'interfaccia, se recente: chi apre
     * l'app a mano molto dopo un tentativo fallito non deve trovarsi un pannello aperto da solo.
     */
    fun takePendingUi(): UiCommand? {
        val (command, at) = pendingUi.getAndSet(null) ?: return null
        return command.takeIf { SystemClock.elapsedRealtime() - at <= PENDING_UI_MAX_AGE_MS }
    }

    /** Messaggio per TV e telefono da altri componenti (es. il player integrato). */
    fun announce(text: String) = toast(text)

    /** File in riproduzione mostrato nel pannello della TV e nei client precedenti alla v1.1. */
    fun setNowPlaying(name: String?) {
        // Informazione della TV, non della cartella: la riportano entrambe le sessioni.
        tv.state.update { it.copy(nowPlaying = name) }
        phone?.state?.update { it.copy(nowPlaying = name) }
    }

    /** Volume smontato o rimosso ([mountPath] null se ignoto): torno alla radice se ci stavo dentro. */
    fun onVolumeRemoved(mountPath: String?) {
        commands.trySend(Command.VolumeRemoved(mountPath))
    }

    /** Nuovo volume montato o permessi cambiati: aggiorno la radice se la sto mostrando. */
    fun onVolumesChanged() {
        commands.trySend(Command.VolumesChanged)
    }

    // ------------------------------------------------------------------ trasferimenti HTTP

    /**
     * Cartella di destinazione di un caricamento dal telefono: quella dell'elenco [listingSeq]
     * che il telefono mostrava quando ha scelto i file. Null se l'elenco è troppo vecchio o era
     * la radice degli archivi (dove non si può scrivere).
     */
    fun uploadFolder(listingSeq: Int): Node? = synchronized(recentListings) { recentListings[listingSeq]?.folder }

    /** File dell'elenco [listingSeq] all'indice [index], per lo scaricamento sul telefono. */
    fun downloadNode(listingSeq: Int, index: Int): Node? = synchronized(recentListings) {
        recentListings[listingSeq]?.nodes?.getOrNull(index)
    }?.takeIf { !it.kind.isContainer }

    /**
     * Occupa la corsia delle scritture per un trasferimento HTTP; false se è già occupata.
     * Chi ottiene true deve chiamare [endTransfer] in ogni caso.
     */
    fun beginTransfer(text: String): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        _status.update { it.copy(opText = text, opPermille = 0, opCancelable = false) }
        return true
    }

    fun updateTransfer(text: String, permille: Int) {
        _status.update { it.copy(opText = text, opPermille = permille) }
    }

    /**
     * Libera la corsia. [changedFolder] non null: il trasferimento ha scritto in quella cartella
     * (caricamento riuscito), quindi la rileggo in TV e telefono selezionando [selectName].
     */
    fun endTransfer(changedFolder: Node?, selectName: String?, message: String?) {
        _status.update { it.copy(opText = null, opPermille = -1, opCancelable = false) }
        busy.set(false)
        message?.let(::toast)
        if (changedFolder != null) commands.trySend(Command.FolderChanged(changedFolder, selectName))
    }

    // ------------------------------------------------------------------ comandi

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Client -> handleClient(command.message, command.fromPhone)
            is Command.SearchBatch -> command.session.onSearchBatch(command.token, command.hits)
            is Command.SearchDone -> command.session.onSearchDone(command.token)
            is Command.VolumeRemoved -> sessions().forEach { it.onVolumeRemoved(command.mountPath) }
            Command.VolumesChanged -> sessions().forEach { if (it.stack.isEmpty()) it.loadCurrent() }
            // Durante una ricerca arrivano più elenchi in fila: rileggo solo per l'ultimo.
            is Command.RefreshProgress -> if (command.listingSeq == command.session.seq) command.session.refreshProgress()
            is Command.ProgressChanged -> sessions().forEach { it.applyProgressChange(command.change) }
            is Command.OpFinished -> {
                if (command.clearClip) setClipboard(emptyList(), move = false)
                command.message?.let(::toast)
                sessions().forEach { it.refreshAfterChange(if (it === command.target) command.selectName else null) }
            }
            is Command.FolderChanged -> sessions().forEach { session ->
                if (session.currentDir()?.ref == command.folder.ref) session.refreshAfterChange(command.selectName)
            }
        }
    }

    /** Sessioni attive: una sola con la sincronizzazione, due senza. */
    private fun sessions(): List<Session> = listOfNotNull(tv, phone)

    private fun handleClient(message: ClientMessage, fromPhone: Boolean) {
        val s = if (fromPhone) phoneSession.value else tv
        when (message) {
            is ClientMessage.Hello -> Unit
            is ClientMessage.Open -> s.nodeAt(message.seq, message.index)?.let { s.open(message.index, it) }
            is ClientMessage.Select -> if (s.nodeAt(message.seq, message.index) != null) s.select(message.index)
            ClientMessage.Up -> s.goUp()
            ClientMessage.Root -> s.goRoot()
            is ClientMessage.Crumb -> s.goCrumb(message.depth)
            is ClientMessage.Query -> s.setQuery(message.text)
            is ClientMessage.Recursive -> s.setRecursive(message.enabled)
            // Il pad è il telecomando della TV: agisce sempre sulla sua sessione.
            is ClientMessage.Key -> tv.handleKey(message.key)
            ClientMessage.Refresh -> s.loadCurrent(selectName = s.selectedName())
            // Comandi del player: passano di qui per mantenere un solo ordine di arrivo con
            // l'apertura dei file, poi il player li esegue sul proprio thread.
            is ClientMessage.Player, is ClientMessage.SeekTo, is ClientMessage.Autoplay,
            is ClientMessage.Resume, is ClientMessage.Layout, is ClientMessage.Volume -> playback.handle(message)
            is ClientMessage.Ui -> handleUi(message.command)
            is ClientMessage.Sync -> setSync(message.enabled)
            is ClientMessage.Scroll -> s.setScroll(message.seq, message.index)
            is ClientMessage.FileOp -> handleFileOp(s, message)
        }
    }

    // ------------------------------------------------------------------ sincronizzazione

    /**
     * Spenta: il telefono riceve una copia della sessione della TV e da lì prosegue da solo.
     * Accesa: la TV adotta la posizione del telefono (cartella, ricerca, selezione, scroll) e le
     * due sessioni tornano una.
     */
    private fun setSync(enabled: Boolean) {
        if (enabled == _status.value.synced) return
        prefs.edit().putBoolean(KEY_SYNCED, enabled).apply()
        _status.update { it.copy(synced = enabled) }
        if (!enabled) {
            detachPhone()
            return
        }
        val p = phone ?: return
        tv.adopt(p)
        p.cancelSearch()
        phone = null
        phoneSession.value = tv
        currentFolder = (tv.currentDir()?.ref as? NodeRef.Posix)?.file
        Log.i(TAG, "Sincronizzazione attiva: la TV adotta la posizione del telefono (${tv.stack.joinToString("/") { it.name }})")
    }

    private fun detachPhone() {
        val p = Session()
        p.adopt(tv)
        phone = p
        phoneSession.value = p
        Log.i(TAG, "Sincronizzazione spenta: telefono e TV navigano in modo indipendente")
    }

    // ------------------------------------------------------------------ comandi ui

    /**
     * Comando all'interfaccia della TV. Con l'interfaccia visibile lo consegno a lei (che lo
     * esegue sul main thread); altrimenti la porto in primo piano e, se il comando riguarda un
     * pannello del player, lo lascio in attesa: l'activity lo applica in onStart.
     */
    private fun handleUi(command: UiCommand) {
        if (command == UiCommand.HOME) goHome()
        val target = remoteUiTarget
        if (target != null) {
            target(command)
            return
        }
        pendingUi.set(if (command.isPlayerPanel) command to SystemClock.elapsedRealtime() else null)
        val launch = uiLauncher
        if (launch != null) launch() else Log.w(TAG, "Nessun avvio dell'interfaccia configurato: comando $command ignorato")
    }

    /**
     * Parte del tasto Home eseguita qui, valida con e senza interfaccia visibile: video a schermo
     * intero ridotto a miniatura (non lo fermo: chi preme Home vuole il file manager, non
     * interrompere il film) e ritorno all'elenco degli archivi della TV, che azzera anche la
     * ricerca. L'apertura dell'app, se non è in primo piano, la fa [handleUi].
     */
    private fun goHome() {
        val pb = playback.state.value
        if (pb.active && pb.view == TvView.FULLSCREEN) playback.handle(ClientMessage.Layout(TvView.MINI))
        if (tv.stack.isNotEmpty() || tv.isShowingResults() || tv.state.value.query.isNotEmpty()) {
            tv.stack.clear()
            tv.navigateTo(selectName = null)
        }
    }

    // ------------------------------------------------------------------ operazioni sui file

    private fun handleFileOp(s: Session, m: ClientMessage.FileOp) {
        when (m.op) {
            FileOpKind.COPY, FileOpKind.CUT -> {
                val node = s.nodeAt(m.seq, m.index) ?: return
                if (node.kind == EntryKind.VOLUME) return toast(context.getString(R.string.fop_err_volume))
                val move = m.op == FileOpKind.CUT
                setClipboard(listOf(ClipItem(node, s.parentOf(m.index))), move)
                toast(context.getString(if (move) R.string.fop_cut else R.string.fop_copied, node.name))
            }
            FileOpKind.CLEAR -> {
                setClipboard(emptyList(), move = false)
                toast(context.getString(R.string.fop_clip_cleared))
            }
            FileOpKind.CANCEL -> opJob?.cancel()
            FileOpKind.PASTE -> {
                val dest = s.currentDir() ?: return toast(context.getString(R.string.fop_err_no_folder))
                val items = clipboard
                if (items.isEmpty()) return toast(context.getString(R.string.fop_err_clip_empty))
                val move = clipMove
                val label = items.first().node.name
                val text = context.getString(if (move) R.string.fop_working_move else R.string.fop_working_copy, label)
                runOperation(s, text, cancelable = true, clearClipOnSuccess = move) { onProgress ->
                    val last = files.transfer(items, dest, move) { p ->
                        onProgress(context.getString(if (move) R.string.fop_working_move else R.string.fop_working_copy, p.currentName), p.permille)
                    }
                    last to context.getString(R.string.fop_done_paste, dest.name)
                }
            }
            FileOpKind.DELETE -> {
                val node = s.nodeAt(m.seq, m.index) ?: return
                if (node.kind == EntryKind.VOLUME) return toast(context.getString(R.string.fop_err_volume))
                // Dopo l'eliminazione seleziono la voce successiva (o la precedente se era l'ultima).
                val neighbour = s.neighbourName(m.index)
                runOperation(s, context.getString(R.string.fop_working_delete, node.name), cancelable = true) { _ ->
                    files.delete(node) {}
                    neighbour to context.getString(R.string.fop_done_delete, node.name)
                }
            }
            FileOpKind.RENAME -> {
                val node = s.nodeAt(m.seq, m.index) ?: return
                if (node.kind == EntryKind.VOLUME) return toast(context.getString(R.string.fop_err_volume))
                val name = validName(m.name) ?: return
                val parent = s.parentOf(m.index)
                // Dopo la rinomina il riferimento negli appunti non sarebbe più valido: lo tolgo
                // (il ciclo dei comandi è l'unico a toccare gli appunti, quindi lo faccio qui).
                if (clipboard.any { it.node.ref == node.ref }) {
                    setClipboard(clipboard.filterNot { it.node.ref == node.ref }, clipMove)
                }
                runOperation(s, context.getString(R.string.fop_working_rename, node.name), cancelable = false) { _ ->
                    val renamed = files.rename(node, parent, name)
                    renamed.name to context.getString(R.string.fop_done_rename, renamed.name)
                }
            }
            FileOpKind.MKDIR -> {
                val dest = s.currentDir() ?: return toast(context.getString(R.string.fop_err_no_folder))
                val name = validName(m.name) ?: return
                runOperation(s, context.getString(R.string.fop_working_mkdir, name), cancelable = false) { _ ->
                    val created = files.createDirectory(dest, name)
                    created.name to context.getString(R.string.fop_done_mkdir, created.name)
                }
            }
        }
    }

    /** Nome validato con le regole comuni, o null dopo aver spiegato il problema. */
    private fun validName(raw: String?): String? {
        return when (val check = FileNames.check(raw.orEmpty())) {
            is FileNames.Check.Valid -> check.name
            is FileNames.Check.Invalid -> {
                toast(
                    context.getString(
                        when (check.error) {
                            FileNames.Problem.EMPTY -> R.string.fop_err_name_empty
                            FileNames.Problem.RESERVED -> R.string.fop_err_name_reserved
                            FileNames.Problem.FORBIDDEN_CHAR -> R.string.fop_err_name_chars
                            FileNames.Problem.TOO_LONG -> R.string.fop_err_name_long
                        },
                    ),
                )
                null
            }
        }
    }

    private fun setClipboard(items: List<ClipItem>, move: Boolean) {
        clipboard = items
        clipMove = move && items.isNotEmpty()
        _status.update { it.copy(clipCount = items.size, clipName = items.firstOrNull()?.node?.name, clipMove = clipMove) }
    }

    /**
     * Esegue un'operazione sui file fuori dal ciclo dei comandi (una copia di un film su una
     * chiavetta dura minuti: TV e telefono devono restare navigabili). [work] riceve il callback
     * di avanzamento e restituisce (voce da selezionare, messaggio di esito). La fine passa dalla
     * coda dei comandi, che rilegge le cartelle di entrambe le sessioni.
     */
    private fun runOperation(
        target: Session,
        text: String,
        cancelable: Boolean,
        clearClipOnSuccess: Boolean = false,
        work: suspend (onProgress: (String, Int) -> Unit) -> Pair<String?, String>,
    ) {
        if (!busy.compareAndSet(false, true)) return toast(context.getString(R.string.fop_err_busy))
        _status.update { it.copy(opText = text, opPermille = -1, opCancelable = cancelable) }
        Log.i(TAG, "Operazione avviata: $text")
        opJob = scope.launch(Dispatchers.IO) {
            var outcome: Command.OpFinished
            try {
                val (select, message) = work { progressText, permille ->
                    _status.update { it.copy(opText = progressText, opPermille = permille) }
                }
                Log.i(TAG, "Operazione conclusa: $message")
                outcome = Command.OpFinished(target, message, select, clearClip = clearClipOnSuccess)
            } catch (e: CancellationException) {
                Log.i(TAG, "Operazione annullata: $text")
                outcome = Command.OpFinished(target, context.getString(R.string.fop_cancelled), null, clearClip = false)
            } catch (e: FileOperationException) {
                Log.w(TAG, "Operazione non riuscita: $text", e)
                if (e.needsFullAccess) reportAccessNeeded()
                outcome = Command.OpFinished(target, e.message, null, clearClip = false)
            } catch (e: Exception) {
                Log.e(TAG, "Errore imprevisto durante: $text", e)
                outcome = Command.OpFinished(target, context.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName), null, clearClip = false)
            }
            _status.update { it.copy(opText = null, opPermille = -1, opCancelable = false) }
            busy.set(false)
            // trySend anche da una coroutine annullata: non sospende.
            commands.trySend(outcome)
        }
    }

    private fun toast(text: String) {
        _toasts.tryEmit(text)
    }

    // ------------------------------------------------------------------ sessione

    /**
     * Stato di navigazione di un osservatore (TV o telefono): percorso, elenco mostrato,
     * ricerca, selezione, scroll. Letto e scritto solo dal ciclo dei comandi.
     */
    private inner class Session {
        val stack = ArrayList<Node>()
        private var dirNodes: List<Node> = emptyList()
        private var shownNodes: List<Node> = emptyList()
        private var hits: List<SearchHit> = emptyList()
        private var searchBase: List<Node> = emptyList()
        private var dirMessage: String? = null
        var seq = 0
            private set
        private var searchJob: Job? = null
        private var searchToken = 0

        // Identificativo di avanzamento (ProgressStore.keyOf) → indice, per i soli file audio/video
        // dell'elenco attuale: traduce un salvataggio del player nella riga da aggiornare.
        private var progressIndexById: Map<String, Int> = emptyMap()

        val listing = MutableStateFlow(Listing.EMPTY)

        // Ricerca nelle sottocartelle attiva di default; se l'utente la spegne la scelta resta.
        val state = MutableStateFlow(RemoteState(recursive = prefs.getBoolean(KEY_RECURSIVE, true)))
        val progress = MutableStateFlow(ListingProgress.EMPTY)

        /** Copia integrale di un'altra sessione (elenco compreso, con la stessa sequenza). */
        fun adopt(other: Session) {
            cancelSearch()
            stack.clear()
            stack.addAll(other.stack)
            dirNodes = other.dirNodes
            shownNodes = other.shownNodes
            hits = other.hits
            searchBase = other.searchBase
            dirMessage = other.dirMessage
            seq = other.seq
            progressIndexById = other.progressIndexById
            listing.value = other.listing.value
            state.value = other.state.value.copy(nowPlaying = state.value.nowPlaying ?: other.state.value.nowPlaying)
            progress.value = other.progress.value
            // Una ricerca ancora in corso nell'altra sessione riparte qui: i suoi risultati
            // arriverebbero all'altra.
            if (other.state.value.searching) restartSearch()
        }

        fun currentDir(): Node? = stack.lastOrNull()

        fun selectedName(): String? = shownNodes.getOrNull(state.value.selected)?.name

        /** Cartella che contiene la voce [index] dell'elenco attuale (null per i volumi). */
        fun parentOf(index: Int): Node? =
            if (isShowingResults()) hits.getOrNull(index)?.ancestors?.lastOrNull() else stack.lastOrNull()

        /** Nome della voce visibile dopo [index] (o prima, se è l'ultima): da selezionare dopo un'eliminazione. */
        fun neighbourName(index: Int): String? {
            val visible = visibleIndices()
            val pos = visible.indexOf(index)
            if (pos < 0) return null
            val other = visible.getOrNull(pos + 1) ?: visible.getOrNull(pos - 1) ?: return null
            return shownNodes.getOrNull(other)?.name
        }

        /** Nodo mostrato all'indice dato, solo se il client si riferisce all'elenco attuale. */
        fun nodeAt(listingSeq: Int, index: Int): Node? {
            if (listingSeq != seq) {
                // Il client ha agito su un elenco superato (es. comando incrociato con un cambio di
                // cartella): ignoro e ripubblico lo stato, che lo riallinea.
                state.update { it.copy(listingSeq = seq) }
                return null
            }
            return shownNodes.getOrNull(index)
        }

        fun open(index: Int, node: Node) {
            if (node.kind.isContainer) {
                // Da un risultato di ricerca entro nella sua cartella ricostruendo il percorso intero.
                val newStack = if (isShowingResults()) {
                    val hit = hits[index]
                    searchBase + hit.ancestors + node
                } else {
                    stack + node
                }
                stack.clear()
                stack.addAll(newStack)
                navigateTo(selectName = null)
                return
            }
            select(index)
            if (MediaTypes.isInternallyPlayable(node.kind, node.name)) {
                playInternally(index, node)
                return
            }
            val result = launcher.open(node)
            if (result.started) setNowPlaying(node.name)
            result.message?.let(::toast)
        }

        /**
         * Playlist = file della stessa famiglia (video o audio) visibili ora nell'elenco, filtro di
         * ricerca compreso, nell'ordine mostrato. Ne passo un'istantanea al player.
         */
        private fun playInternally(index: Int, node: Node) {
            val items = ArrayList<Node>()
            var start = 0
            for (i in visibleIndices()) {
                val candidate = shownNodes.getOrNull(i) ?: continue
                if (candidate.kind != node.kind || !MediaTypes.isInternallyPlayable(candidate.kind, candidate.name)) continue
                if (i == index) start = items.size
                items += candidate
            }
            // La voce aperta può non essere tra le visibili (selezione arrivata prima del filtro).
            if (items.getOrNull(start) !== node) {
                items.clear()
                items += node
                start = 0
            }
            playback.play(items, start)
        }

        fun goUp() {
            if (stack.isEmpty()) {
                // Alla radice "su" annulla soltanto un'eventuale ricerca in corso.
                if (state.value.query.isNotEmpty()) setQuery("")
                return
            }
            val child = stack.removeAt(stack.lastIndex)
            navigateTo(selectName = child.name)
        }

        fun goRoot() {
            if (stack.isNotEmpty() || isShowingResults()) {
                stack.clear()
                navigateTo(selectName = null)
            }
        }

        fun goCrumb(depth: Int) {
            if (depth !in 0 until stack.size) return
            val child = stack[depth].name
            while (stack.size > depth) stack.removeAt(stack.lastIndex)
            navigateTo(selectName = child)
        }

        fun handleKey(key: RemoteKey) {
            val selectedNode = shownNodes.getOrNull(state.value.selected)
            when (key) {
                RemoteKey.UP -> moveSelection(-1)
                RemoteKey.DOWN -> moveSelection(+1)
                RemoteKey.LEFT -> goUp()
                RemoteKey.RIGHT -> if (selectedNode?.kind?.isContainer == true) open(state.value.selected, selectedNode)
                RemoteKey.OK -> if (selectedNode != null) open(state.value.selected, selectedNode)
                RemoteKey.BACK -> if (state.value.query.isNotEmpty()) setQuery("") else goUp()
            }
        }

        private fun moveSelection(delta: Int) {
            val visible = visibleIndices()
            if (visible.isEmpty()) return
            val pos = visible.indexOf(state.value.selected)
            val next = if (pos < 0) 0 else (pos + delta).coerceIn(0, visible.size - 1)
            select(visible[next])
        }

        fun select(index: Int) {
            state.update { it.copy(selected = index, listingSeq = seq) }
        }

        /** Prima riga visibile sul telefono: la TV la porta in cima (vedi `RemoteState.scrollTop`). */
        fun setScroll(listingSeq: Int, index: Int) {
            if (listingSeq != seq || index !in shownNodes.indices) return
            if (state.value.scrollTop != index) state.update { it.copy(scrollTop = index) }
        }

        fun onVolumeRemoved(mountPath: String?) {
            val inside = stack.firstOrNull()?.let { root -> mountPath == null || storage.isUnder(root, mountPath) } ?: false
            if (inside) {
                stack.clear()
                if (this === tv) toast(context.getString(R.string.volume_removed))
                navigateTo(selectName = null)
            } else if (stack.isEmpty()) {
                loadCurrent()
            }
        }

        /**
         * Rilettura dopo una modifica dei file: tolgo dal percorso le cartelle che non esistono
         * più (eliminate, spostate o rinominate dall'altra sessione), poi ricarico mantenendo la
         * selezione per nome, o selezionando [selectName] se indicato.
         */
        fun refreshAfterChange(selectName: String?) {
            var pruned = false
            while (stack.isNotEmpty() && !files.exists(stack.last())) {
                stack.removeAt(stack.lastIndex)
                pruned = true
            }
            if (pruned) navigateTo(selectName = null) else loadCurrent(selectName = selectName ?: selectedName())
        }

        // -------------------------------------------------------------- ricerca

        fun setQuery(text: String) {
            val query = text.take(200)
            if (query == state.value.query) return
            state.update { it.copy(query = query) }
            if (state.value.recursive) restartSearch() else ensureSelectionVisible()
        }

        fun setRecursive(enabled: Boolean) {
            if (enabled == state.value.recursive) return
            state.update { it.copy(recursive = enabled) }
            prefs.edit().putBoolean(KEY_RECURSIVE, enabled).apply()
            restartSearch()
        }

        fun restartSearch() {
            cancelSearch()
            val query = state.value.query
            if (!state.value.recursive || SearchText.tokens(query).isEmpty()) {
                // Nessuna ricerca ricorsiva: torno all'elenco della cartella, filtrato dalla query.
                if (isShowingResults()) showDirectory(selectName = null) else ensureSelectionVisible()
                state.update { it.copy(searching = false) }
                return
            }
            val token = ++searchToken
            // Alla radice cerco in tutti i volumi; altrimenti nella cartella corrente e sotto.
            val roots = if (stack.isEmpty()) dirNodes else listOf(stack.last())
            searchBase = if (stack.isEmpty()) emptyList() else stack.dropLast(1)
            state.update { it.copy(searching = true) }
            val session = this
            searchJob = scope.launch(Dispatchers.IO) {
                // Attesa breve: chi digita una parola non avvia una scansione del disco a ogni lettera.
                delay(SEARCH_DEBOUNCE_MS)
                commands.trySend(Command.SearchBatch(session, token, emptyList()))
                storage.search(roots, query, Protocol.MAX_SEARCH_RESULTS) { batch ->
                    commands.send(Command.SearchBatch(session, token, batch))
                }
                commands.send(Command.SearchDone(session, token))
            }
        }

        fun cancelSearch() {
            searchJob?.cancel()
            searchJob = null
            searchToken++
        }

        fun onSearchBatch(token: Int, batch: List<SearchHit>) {
            if (token != searchToken) return
            if (!isShowingResults()) {
                // Primo blocco (anche vuoto): passo dall'elenco della cartella ai risultati.
                hits = emptyList()
                state.update { it.copy(selected = -1) }
            }
            hits = hits + batch
            shownNodes = hits.map { it.node }
            publishListing(complete = false, searchResults = true)
            if (state.value.selected < 0 && hits.isNotEmpty()) select(0)
        }

        fun onSearchDone(token: Int) {
            if (token != searchToken) return
            publishListing(complete = true, searchResults = true)
            state.update { it.copy(searching = false, listingSeq = seq) }
        }

        fun isShowingResults(): Boolean = listing.value.searchResults

        // -------------------------------------------------------------- caricamento

        /** Cambio di cartella: azzero la query (il filtro riguardava la cartella precedente) e ricarico. */
        fun navigateTo(selectName: String?) {
            cancelSearch()
            state.update { it.copy(query = "", searching = false) }
            loadCurrent(selectName)
        }

        fun loadCurrent(selectName: String? = null) {
            cancelSearch()
            if (this === tv) currentFolder = (stack.lastOrNull()?.ref as? NodeRef.Posix)?.file
            dirMessage = null
            dirNodes = try {
                if (stack.isEmpty()) storage.volumes() else storage.list(stack.last())
            } catch (e: StorageAccessException) {
                dirMessage = e.message
                emptyList()
            }
            if (dirMessage == null && stack.isEmpty() && dirNodes.isEmpty()) {
                dirMessage = context.getString(R.string.no_volumes)
            }
            showDirectory(selectName)
            if (state.value.recursive && SearchText.tokens(state.value.query).isNotEmpty()) restartSearch()
        }

        private fun showDirectory(selectName: String?) {
            hits = emptyList()
            shownNodes = dirNodes
            publishListing(complete = true, searchResults = false)
            val visible = visibleIndices()
            val preferred = selectName?.let { name -> shownNodes.indexOfFirst { it.name == name } }?.takeIf { it in visible }
            state.update {
                it.copy(
                    listingSeq = seq,
                    selected = preferred ?: visible.firstOrNull() ?: -1,
                    message = dirMessage,
                )
            }
        }

        private fun publishListing(complete: Boolean, searchResults: Boolean) {
            seq = ++seqCounter
            val crumbs = stack.map { it.name }
            val entries = if (searchResults) {
                // Nei risultati il dettaglio indica dove si trova la voce rispetto alla cartella di
                // partenza: alla radice il percorso parte dal volume, altrimenti dalla cartella
                // corrente (che è il primo antenato e quindi lo ometto).
                hits.map { hit ->
                    val relative = hit.ancestors.drop(if (stack.isEmpty()) 0 else 1).joinToString("/") { it.name }
                    hit.node.toEntry(relative.ifEmpty { stack.lastOrNull()?.name })
                }
            } else {
                shownNodes.map { it.toEntry() }
            }
            synchronized(recentListings) { recentListings[seq] = ListingRef(stack.lastOrNull(), shownNodes) }
            listing.value = Listing(seq, crumbs, entries, searchResults = searchResults, complete = complete)
            // Elenco nuovo: lo scroll indicato dal telefono si riferiva al precedente.
            state.update { it.copy(listingSeq = seq, scrollTop = -1) }
            // Leggo gli avanzamenti con un comando successivo: l'elenco parte subito, le barre
            // arrivano un istante dopo in un messaggio a parte.
            commands.trySend(Command.RefreshProgress(this, seq))
        }

        // -------------------------------------------------------------- avanzamento

        /** Rilegge dall'archivio la parte vista dei file audio/video dell'elenco attuale. */
        fun refreshProgress() {
            val indexById = HashMap<String, Int>()
            val media = ArrayList<Node>()
            shownNodes.forEachIndexed { index, node ->
                if (node.kind == EntryKind.VIDEO || node.kind == EntryKind.AUDIO) {
                    indexById[ProgressStore.keyOf(node)] = index
                    media += node
                }
            }
            progressIndexById = indexById
            val saved = progressStore.loadPermille(media)
            val permille = HashMap<Int, Int>(saved.size)
            saved.forEach { (id, value) -> indexById[id]?.let { index -> permille[index] = value } }
            progress.value = ListingProgress(seq, permille)
        }

        /** Un salvataggio del player: aggiorno la sola voce interessata, se è nell'elenco attuale. */
        fun applyProgressChange(change: ProgressChange) {
            val current = progress.value
            // Rilettura ancora in coda per l'elenco attuale: leggerà anche questo valore dal disco.
            if (current.seq != seq) return
            val index = progressIndexById[change.key] ?: return
            val now = current.of(index)
            if (now == change.permille || (change.permille <= 0 && now < 0)) return
            val permille = HashMap(current.permille)
            if (change.permille > 0) permille[index] = change.permille else permille.remove(index)
            progress.value = ListingProgress(seq, permille)
        }

        private fun visibleIndices(): IntArray = SearchText.visibleIndices(listing.value.entries, state.value.query)

        private fun ensureSelectionVisible() {
            val visible = visibleIndices()
            val current = state.value.selected
            if (current !in visible) state.update { it.copy(selected = visible.firstOrNull() ?: -1, listingSeq = seq) }
        }
    }

    private companion object {
        const val TAG = "BrowserController"
        const val SEARCH_DEBOUNCE_MS = 250L

        /** Oltre il tempo massimo che serve alla TV per aprire l'app su comando del telefono. */
        const val PENDING_UI_MAX_AGE_MS = 15_000L

        /** Elenchi ricordati per gli endpoint HTTP: abbondano per una serie di caricamenti. */
        const val RECENT_LISTINGS = 48

        const val PREFS = "browser"
        const val KEY_RECURSIVE = "recursive"
        const val KEY_SYNCED = "synced"
    }
}
