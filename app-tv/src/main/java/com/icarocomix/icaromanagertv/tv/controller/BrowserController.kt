package com.icarocomix.icaromanagertv.tv.controller

import android.content.Context
import android.util.Log
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import com.icarocomix.icaromanagertv.common.protocol.Listing
import com.icarocomix.icaromanagertv.common.protocol.ListingProgress
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import com.icarocomix.icaromanagertv.common.protocol.RemoteKey
import com.icarocomix.icaromanagertv.common.protocol.RemoteState
import com.icarocomix.icaromanagertv.common.search.SearchText
import com.icarocomix.icaromanagertv.tv.R
import com.icarocomix.icaromanagertv.tv.playback.PlaybackController
import com.icarocomix.icaromanagertv.tv.playback.ProgressChange
import com.icarocomix.icaromanagertv.tv.playback.ProgressStore
import com.icarocomix.icaromanagertv.tv.storage.MediaTypes
import com.icarocomix.icaromanagertv.tv.storage.Node
import com.icarocomix.icaromanagertv.tv.storage.NodeRef
import com.icarocomix.icaromanagertv.tv.storage.SearchHit
import com.icarocomix.icaromanagertv.tv.storage.StorageAccessException
import com.icarocomix.icaromanagertv.tv.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Stato autorevole del file manager della TV. Telefono e interfaccia della TV inviano gli stessi
 * comandi ([ClientMessage]); il controller li applica uno alla volta, nell'ordine di arrivo, su
 * un'unica coroutine: nessun lock e nessuna corsa tra telefono e telecomando della TV.
 *
 * Pubblica flussi separati: [listing] (elenco, cambia di rado), [state] (query, selezione,
 * messaggi: cambia a ogni tasto) e [progress] (parte vista dei file elencati: cambia a ogni
 * salvataggio del player). Server e UI della TV li osservano tutti.
 */
class BrowserController(
    private val context: Context,
    private val storage: StorageRepository,
    private val launcher: MediaLauncher,
    private val playback: PlaybackController,
    private val progressStore: ProgressStore,
    private val scope: CoroutineScope,
) {

    private sealed interface Command {
        data class Client(val message: ClientMessage) : Command
        data class SearchBatch(val token: Int, val hits: List<SearchHit>) : Command
        data class SearchDone(val token: Int) : Command
        data class VolumeRemoved(val mountPath: String?) : Command
        data object VolumesChanged : Command
        data class RefreshProgress(val listingSeq: Int) : Command
        data class ProgressChanged(val change: ProgressChange) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val _listing = MutableStateFlow(Listing.EMPTY)
    val listing: StateFlow<Listing> = _listing.asStateFlow()

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(ListingProgress.EMPTY)
    val progress: StateFlow<ListingProgress> = _progress.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    // Stato interno: letto e scritto solo dalla coroutine del ciclo dei comandi.
    private val stack = ArrayList<Node>()
    private var dirNodes: List<Node> = emptyList()
    private var shownNodes: List<Node> = emptyList()
    private var hits: List<SearchHit> = emptyList()
    private var searchBase: List<Node> = emptyList()
    private var dirMessage: String? = null
    private var seq = 0
    private var searchJob: Job? = null
    private var searchToken = 0
    private var started = false

    // Identificativo di avanzamento (ProgressStore.keyOf) → indice, per i soli file audio/video
    // dell'elenco attuale: traduce un salvataggio del player nella riga da aggiornare.
    private var progressIndexById: Map<String, Int> = emptyMap()

    /**
     * Cartella del disco aperta ora, se è un percorso POSIX (null alla radice o in un albero SAF).
     * Scritta solo dal ciclo dei comandi, letta dalla UI per salvarvi i log: volatile basta,
     * è un riferimento immutabile.
     */
    @Volatile
    var currentFolder: java.io.File? = null
        private set

    /** Avvia il ciclo dei comandi e carica la radice. Idempotente. */
    fun start() {
        if (started) return
        started = true
        // Anche le notifiche dell'archivio passano dalla coda: la mappa degli indici e l'elenco
        // attuale restano di proprietà esclusiva del ciclo dei comandi.
        scope.launch {
            progressStore.changes.collect { change -> commands.trySend(Command.ProgressChanged(change)) }
        }
        scope.launch(Dispatchers.IO) {
            loadCurrent()
            for (command in commands) {
                // Un errore imprevisto su un comando non deve fermare il ciclo: lo registro e
                // proseguo, altrimenti la TV smetterebbe di rispondere a tutti i client.
                try {
                    handle(command)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Comando non eseguito: $command", e)
                    toast(context.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Accoda un comando; chiamabile da qualunque thread (server, UI). */
    fun submit(message: ClientMessage) {
        commands.trySend(Command.Client(message))
    }

    /**
     * Interfaccia della TV in primo piano che vuole ricevere i tasti del pad del telefono come
     * se arrivassero dal telecomando fisico. Impostato dall'activity in onStart e tolto in onStop.
     * Volatile: scritto dal main thread, letto dai thread del server.
     */
    @Volatile
    var remoteKeyTarget: ((RemoteKey) -> Unit)? = null

    /**
     * Ingresso dei comandi provenienti dal telefono. Differisce da [submit] solo per i tasti del
     * pad: con l'interfaccia della TV visibile li consegno a lei, che decide se trattarli come
     * navigazione dell'elenco (rimandandoli a [submit]) o come veri tasti D-pad (player a schermo
     * intero, pannello laterale, finestre di dialogo). Senza interfaccia visibile, cioè con il
     * solo servizio attivo, restano comandi di navigazione dell'elenco come prima.
     */
    fun submitFromRemote(message: ClientMessage) {
        val target = remoteKeyTarget
        if (message is ClientMessage.Key && target != null) {
            target(message.key)
        } else {
            submit(message)
        }
    }

    /** Messaggio per TV e telefono da altri componenti (es. il player integrato). */
    fun announce(text: String) = toast(text)

    /** File in riproduzione mostrato nel pannello della TV e nei client precedenti alla v1.1. */
    fun setNowPlaying(name: String?) {
        _state.update { it.copy(nowPlaying = name) }
    }

    /** Volume smontato o rimosso ([mountPath] null se ignoto): torno alla radice se ci stavo dentro. */
    fun onVolumeRemoved(mountPath: String?) {
        commands.trySend(Command.VolumeRemoved(mountPath))
    }

    /** Nuovo volume montato o permessi cambiati: aggiorno la radice se la sto mostrando. */
    fun onVolumesChanged() {
        commands.trySend(Command.VolumesChanged)
    }

    // ------------------------------------------------------------------ comandi

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Client -> handleClient(command.message)
            is Command.SearchBatch -> if (command.token == searchToken) appendHits(command.hits)
            is Command.SearchDone -> if (command.token == searchToken) {
                publishListing(complete = true, searchResults = true)
                _state.update { it.copy(searching = false, listingSeq = seq) }
            }
            is Command.VolumeRemoved -> {
                val inside = stack.firstOrNull()?.let { root ->
                    command.mountPath == null || storage.isUnder(root, command.mountPath)
                } ?: false
                if (inside) {
                    stack.clear()
                    toast(context.getString(R.string.volume_removed))
                    navigateTo(selectName = null)
                } else if (stack.isEmpty()) {
                    loadCurrent()
                }
            }
            Command.VolumesChanged -> if (stack.isEmpty()) loadCurrent()
            // Durante una ricerca arrivano più elenchi in fila: rileggo solo per l'ultimo.
            is Command.RefreshProgress -> if (command.listingSeq == seq) refreshProgress()
            is Command.ProgressChanged -> applyProgressChange(command.change)
        }
    }

    private fun handleClient(message: ClientMessage) {
        when (message) {
            is ClientMessage.Hello -> Unit
            is ClientMessage.Open -> nodeAt(message.seq, message.index)?.let { open(message.index, it) }
            is ClientMessage.Select -> if (nodeAt(message.seq, message.index) != null) select(message.index)
            ClientMessage.Up -> goUp()
            ClientMessage.Root -> if (stack.isNotEmpty() || isShowingResults()) {
                stack.clear()
                navigateTo(selectName = null)
            }
            is ClientMessage.Crumb -> if (message.depth in 0 until stack.size) {
                val child = stack[message.depth].name
                while (stack.size > message.depth) stack.removeAt(stack.lastIndex)
                navigateTo(selectName = child)
            }
            is ClientMessage.Query -> setQuery(message.text)
            is ClientMessage.Recursive -> setRecursive(message.enabled)
            is ClientMessage.Key -> handleKey(message.key)
            ClientMessage.Refresh -> loadCurrent(selectName = shownNodes.getOrNull(_state.value.selected)?.name)
            // Comandi del player: passano di qui per mantenere un solo ordine di arrivo con
            // l'apertura dei file, poi il player li esegue sul proprio thread.
            is ClientMessage.Player, is ClientMessage.SeekTo, is ClientMessage.Autoplay,
            is ClientMessage.Resume, is ClientMessage.Layout, is ClientMessage.Volume -> playback.handle(message)
        }
    }

    /** Nodo mostrato all'indice dato, solo se il client si riferisce all'elenco attuale. */
    private fun nodeAt(listingSeq: Int, index: Int): Node? {
        if (listingSeq != seq) {
            // Il client ha agito su un elenco superato (es. comando incrociato con un cambio di
            // cartella): ignoro e ripubblico lo stato, che lo riallinea.
            _state.update { it.copy(listingSeq = seq) }
            return null
        }
        return shownNodes.getOrNull(index)
    }

    private fun open(index: Int, node: Node) {
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
        if (result.started) _state.update { it.copy(nowPlaying = node.name) }
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

    private fun goUp() {
        if (stack.isEmpty()) {
            // Alla radice "su" annulla soltanto un'eventuale ricerca in corso.
            if (_state.value.query.isNotEmpty()) setQuery("")
            return
        }
        val child = stack.removeAt(stack.lastIndex)
        navigateTo(selectName = child.name)
    }

    private fun handleKey(key: RemoteKey) {
        val selectedNode = shownNodes.getOrNull(_state.value.selected)
        when (key) {
            RemoteKey.UP -> moveSelection(-1)
            RemoteKey.DOWN -> moveSelection(+1)
            RemoteKey.LEFT -> goUp()
            RemoteKey.RIGHT -> if (selectedNode?.kind?.isContainer == true) open(_state.value.selected, selectedNode)
            RemoteKey.OK -> if (selectedNode != null) open(_state.value.selected, selectedNode)
            RemoteKey.BACK -> if (_state.value.query.isNotEmpty()) setQuery("") else goUp()
        }
    }

    private fun moveSelection(delta: Int) {
        val visible = visibleIndices()
        if (visible.isEmpty()) return
        val pos = visible.indexOf(_state.value.selected)
        val next = if (pos < 0) 0 else (pos + delta).coerceIn(0, visible.size - 1)
        select(visible[next])
    }

    private fun select(index: Int) {
        _state.update { it.copy(selected = index, listingSeq = seq) }
    }

    // ------------------------------------------------------------------ ricerca

    private fun setQuery(text: String) {
        val query = text.take(200)
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        if (_state.value.recursive) restartSearch() else ensureSelectionVisible()
    }

    private fun setRecursive(enabled: Boolean) {
        if (enabled == _state.value.recursive) return
        _state.update { it.copy(recursive = enabled) }
        restartSearch()
    }

    private fun restartSearch() {
        cancelSearch()
        val query = _state.value.query
        if (!_state.value.recursive || SearchText.tokens(query).isEmpty()) {
            // Nessuna ricerca ricorsiva: torno all'elenco della cartella, filtrato dalla query.
            if (isShowingResults()) showDirectory(selectName = null) else ensureSelectionVisible()
            _state.update { it.copy(searching = false) }
            return
        }
        val token = ++searchToken
        // Alla radice cerco in tutti i volumi; altrimenti nella cartella corrente e sotto.
        val roots = if (stack.isEmpty()) dirNodes else listOf(stack.last())
        val base = if (stack.isEmpty()) emptyList() else stack.dropLast(1)
        searchBase = base
        _state.update { it.copy(searching = true) }
        searchJob = scope.launch(Dispatchers.IO) {
            // Attesa breve: chi digita una parola non avvia una scansione del disco a ogni lettera.
            delay(SEARCH_DEBOUNCE_MS)
            commands.trySend(Command.SearchBatch(token, emptyList()))
            storage.search(roots, query, Protocol.MAX_SEARCH_RESULTS) { batch ->
                commands.send(Command.SearchBatch(token, batch))
            }
            commands.send(Command.SearchDone(token))
        }
    }

    private fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        searchToken++
    }

    private fun appendHits(batch: List<SearchHit>) {
        if (!isShowingResults()) {
            // Primo blocco (anche vuoto): passo dall'elenco della cartella ai risultati.
            hits = emptyList()
            _state.update { it.copy(selected = -1) }
        }
        hits = hits + batch
        shownNodes = hits.map { it.node }
        publishListing(complete = false, searchResults = true)
        if (_state.value.selected < 0 && hits.isNotEmpty()) select(0)
    }

    private fun isShowingResults(): Boolean = _listing.value.searchResults

    // ------------------------------------------------------------------ caricamento

    /** Cambio di cartella: azzero la query (il filtro riguardava la cartella precedente) e ricarico. */
    private fun navigateTo(selectName: String?) {
        cancelSearch()
        _state.update { it.copy(query = "", searching = false) }
        loadCurrent(selectName)
    }

    private fun loadCurrent(selectName: String? = null) {
        cancelSearch()
        currentFolder = (stack.lastOrNull()?.ref as? NodeRef.Posix)?.file
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
        if (_state.value.recursive && SearchText.tokens(_state.value.query).isNotEmpty()) restartSearch()
    }

    private fun showDirectory(selectName: String?) {
        hits = emptyList()
        shownNodes = dirNodes
        publishListing(complete = true, searchResults = false)
        val visible = visibleIndices()
        val preferred = selectName?.let { name -> shownNodes.indexOfFirst { it.name == name } }?.takeIf { it in visible }
        _state.update {
            it.copy(
                listingSeq = seq,
                selected = preferred ?: visible.firstOrNull() ?: -1,
                message = dirMessage,
            )
        }
    }

    private fun publishListing(complete: Boolean, searchResults: Boolean) {
        seq++
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
        _listing.value = Listing(seq, crumbs, entries, searchResults = searchResults, complete = complete)
        _state.update { it.copy(listingSeq = seq) }
        // Leggo gli avanzamenti con un comando successivo: l'elenco parte subito, le barre
        // arrivano un istante dopo in un messaggio a parte.
        commands.trySend(Command.RefreshProgress(seq))
    }

    // ------------------------------------------------------------------ avanzamento

    /** Rilegge dall'archivio la parte vista dei file audio/video dell'elenco attuale. */
    private fun refreshProgress() {
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
        _progress.value = ListingProgress(seq, permille)
    }

    /** Un salvataggio del player: aggiorno la sola voce interessata, se è nell'elenco attuale. */
    private fun applyProgressChange(change: ProgressChange) {
        val current = _progress.value
        // Rilettura ancora in coda per l'elenco attuale: leggerà anche questo valore dal disco.
        if (current.seq != seq) return
        val index = progressIndexById[change.key] ?: return
        val now = current.of(index)
        if (now == change.permille || (change.permille <= 0 && now < 0)) return
        val permille = HashMap(current.permille)
        if (change.permille > 0) permille[index] = change.permille else permille.remove(index)
        _progress.value = ListingProgress(seq, permille)
    }

    private fun visibleIndices(): IntArray = SearchText.visibleIndices(_listing.value.entries, _state.value.query)

    private fun ensureSelectionVisible() {
        val visible = visibleIndices()
        val current = _state.value.selected
        if (current !in visible) _state.update { it.copy(selected = visible.firstOrNull() ?: -1, listingSeq = seq) }
    }

    private fun toast(text: String) {
        _toasts.tryEmit(text)
    }

    private companion object {
        const val TAG = "BrowserController"
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
