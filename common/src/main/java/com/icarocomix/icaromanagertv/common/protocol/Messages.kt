package com.icarocomix.icaromanagertv.common.protocol

/**
 * Voce di un elenco. Il client non conosce percorsi né URI: identifica una voce con la coppia
 * (sequenza dell'elenco, indice), che il server convalida. Così un client non può chiedere di
 * aprire nulla che la TV non stia già mostrando.
 *
 * [detail]: informazione accessoria (cartella di appartenenza nei risultati di ricerca,
 * descrizione del volume alla radice).
 */
data class Entry(
    val name: String,
    val kind: EntryKind,
    val size: Long = -1,
    val modified: Long = 0,
    val detail: String? = null,
)

/**
 * Contenuto mostrato dalla TV: cartella corrente o risultati di una ricerca ricorsiva.
 * [seq] cresce a ogni nuovo elenco; [crumbs] è il percorso (vuoto = radice con i volumi).
 */
data class Listing(
    val seq: Int,
    val crumbs: List<String>,
    val entries: List<Entry>,
    val searchResults: Boolean = false,
    val complete: Boolean = true,
) {
    companion object {
        val EMPTY = Listing(seq = 0, crumbs = emptyList(), entries = emptyList())
    }
}

/**
 * Avanzamento di riproduzione delle voci dell'elenco [seq], per la barra nelle righe. Viaggia in
 * un messaggio proprio (`progress`, dalla v1.2): i salvataggi durante la riproduzione lo
 * aggiornano ogni pochi secondi e non devono ritrasmettere l'elenco né cambiarne la sequenza.
 *
 * [permille]: indice in `Listing.entries` → avanzamento in millesimi (1..999). Mappa sparsa:
 * contiene solo i file con una posizione salvata, di solito pochi anche in cartelle enormi.
 */
data class ListingProgress(val seq: Int, val permille: Map<Int, Int>) {
    /** Avanzamento della voce [index] in millesimi, -1 se assente. */
    fun of(index: Int): Int = permille[index] ?: -1

    companion object {
        val EMPTY = ListingProgress(seq = 0, permille = emptyMap())

        /** Millesimi da posizione e durata; -1 se la durata è ignota. Mai 0 né 1000: la barra resta visibile e mai piena. */
        fun permilleOf(positionMs: Long, durationMs: Long): Int =
            if (durationMs <= 0 || positionMs < 0) -1 else (positionMs * 1000 / durationMs).toInt().coerceIn(1, 999)
    }
}

/**
 * Stato interattivo della TV, piccolo e frequente: viaggia separato da [Listing] così la
 * digitazione o lo spostamento della selezione non ritrasmettono l'elenco intero.
 *
 * [selected] è l'indice in `Listing.entries` (non nell'elenco filtrato), -1 se nessuna voce.
 */
data class RemoteState(
    val listingSeq: Int = 0,
    val query: String = "",
    val recursive: Boolean = false,
    val searching: Boolean = false,
    val selected: Int = -1,
    val message: String? = null,
    val nowPlaying: String? = null,
)

/** Messaggi dalla TV al telefono. */
sealed class ServerMessage {
    data class Hello(val protocol: Int, val name: String, val appVersion: String, val id: String) : ServerMessage()
    data class ListingMsg(val listing: Listing) : ServerMessage()
    data class StateMsg(val state: RemoteState) : ServerMessage()
    data class Toast(val text: String) : ServerMessage()

    /** Stato del player integrato della TV (dalla v1.1: i client precedenti lo ignorano). */
    data class PlaybackMsg(val playback: PlaybackState) : ServerMessage()

    /** Avanzamento delle voci dell'elenco (dalla v1.2: i client precedenti lo ignorano). */
    data class ProgressMsg(val progress: ListingProgress) : ServerMessage()

    /** Errore fatale lato protocollo (es. versione incompatibile): il server chiude subito dopo. */
    data class Error(val text: String) : ServerMessage()
}

/** Messaggi dal telefono alla TV. */
sealed class ClientMessage {
    data class Hello(val protocol: Int, val name: String) : ClientMessage()

    /** Apre la voce: entra in volume/cartella, avvia la riproduzione di un file. */
    data class Open(val seq: Int, val index: Int) : ClientMessage()

    /** Seleziona la voce (evidenza sulla TV senza aprirla). */
    data class Select(val seq: Int, val index: Int) : ClientMessage()
    data object Up : ClientMessage()
    data object Root : ClientMessage()

    /** Risale al livello [depth] del percorso (0 = radice). */
    data class Crumb(val depth: Int) : ClientMessage()
    data class Query(val text: String) : ClientMessage()
    data class Recursive(val enabled: Boolean) : ClientMessage()
    data class Key(val key: RemoteKey) : ClientMessage()
    data object Refresh : ClientMessage()

    // ---- Player integrato (v1.1). Un server precedente li scarta come messaggi sconosciuti.

    /** Comando di trasporto sul file in riproduzione (pausa, salti, precedente/successivo…). */
    data class Player(val action: PlayerAction) : ClientMessage()

    /** Posizionamento assoluto (barra di avanzamento del telefono). */
    data class SeekTo(val positionMs: Long) : ClientMessage()

    /** Attiva o disattiva il passaggio automatico al file successivo della playlist. */
    data class Autoplay(val enabled: Boolean) : ClientMessage()

    /** Risposta alla domanda di ripresa: true = riprendi dal punto salvato, false = dall'inizio. */
    data class Resume(val fromSaved: Boolean) : ClientMessage()

    /** Disposizione del player sullo schermo della TV (schermo intero o miniatura). */
    data class Layout(val view: TvView) : ClientMessage()

    /** Volume di sistema della TV a un livello assoluto, 0..`PlaybackState.volumeMax` (v1.4). */
    data class Volume(val level: Int) : ClientMessage()
}

/**
 * Stato del player integrato della TV. Viaggia in un messaggio proprio (`playback`): durante la
 * riproduzione la posizione cambia ogni secondo e non deve ritrasmettere né elenco né stato del
 * file manager.
 *
 * - [index]/[count]: posizione nella playlist (i file della stessa famiglia della cartella o dei
 *   risultati mostrati quando la riproduzione è iniziata);
 * - [resumeFromMs] ≥ 0: la TV attende la scelta "riprendi / ricomincia" per il file corrente;
 * - [view]: disposizione sulla TV, condivisa così il telefono può cambiarla;
 * - [volume]/[volumeMax]/[muted] (v1.4): volume di sistema della TV. [volume] = -1 quando il
 *   livello non è regolabile in assoluto (volume fisso dei box HDMI, gestito dal televisore via
 *   CEC): restano utilizzabili solo i comandi relativi su/giù/muto.
 */
data class PlaybackState(
    val active: Boolean = false,
    val title: String? = null,
    val kind: EntryKind = EntryKind.VIDEO,
    val positionMs: Long = 0,
    val durationMs: Long = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val index: Int = -1,
    val count: Int = 0,
    val autoplay: Boolean = true,
    val view: TvView = TvView.FULLSCREEN,
    val resumeFromMs: Long = -1,
    val volume: Int = -1,
    val volumeMax: Int = 0,
    val muted: Boolean = false,
) {
    /** Livello assoluto noto e regolabile: il telefono può mostrare il cursore. */
    val volumeAdjustable: Boolean get() = volumeMax > 0 && volume in 0..volumeMax

    val hasPrevious: Boolean get() = active && index > 0
    val hasNext: Boolean get() = active && index in 0 until count - 1
    val resumePending: Boolean get() = active && resumeFromMs >= 0

    companion object {
        val IDLE = PlaybackState()
    }
}
