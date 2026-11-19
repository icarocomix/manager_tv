package com.icarocomix.icaromanagertv.common.protocol

/**
 * Costanti condivise del protocollo tra app TV (server) e app mobile (client).
 *
 * Trasporto: WebSocket su HTTP in chiaro nella rete locale, messaggi di testo JSON (vedi
 * [ProtocolCodec]). Scoperta: NSD/mDNS con tipo [SERVICE_TYPE] e, in parallelo, beacon UDP in
 * broadcast su [BEACON_PORT] per le reti in cui il multicast viene filtrato dal router.
 */
object Protocol {

    /**
     * Versione del protocollo. Va incrementata (insieme alla MAJOR dell'app) solo per modifiche
     * non retrocompatibili dei messaggi: il server rifiuta i client con versione diversa.
     */
    const val VERSION = 1

    /**
     * Firma dei pacchetti UDP: scarto qualunque datagramma che non la riporti. È un dettaglio
     * del protocollo, mai mostrato: resta il nome storico per non rompere la compatibilità tra
     * versioni di TV e telefono.
     */
    const val MAGIC = "IcaroManagerTV"

    /** Tipo di servizio DNS-SD: il nome dell'etichetta resta entro i 15 caratteri dell'RFC 6763. */
    const val SERVICE_TYPE = "_icaromgrtv._tcp."

    /**
     * Prefisso del nome del servizio annunciato, seguito dal nome del televisore. È visibile
     * nei browser di rete, quindi segue il nome dell'app (v1.8).
     */
    const val SERVICE_NAME_PREFIX = "Manager TV · "

    /** Prefisso delle TV precedenti alla v1.8: il telefono le riconosce ancora. */
    const val LEGACY_SERVICE_NAME_PREFIX = "IcaroManagerTV · "

    /** Nome del televisore da un nome di servizio annunciato, o null se non è una TV dell'app. */
    fun tvNameFromService(serviceName: String): String? =
        listOf(SERVICE_NAME_PREFIX, LEGACY_SERVICE_NAME_PREFIX)
            .firstOrNull { serviceName.startsWith(it.trim()) }
            ?.let { serviceName.removePrefix(it).removePrefix(it.trim()).trim() }

    /** Attributo TXT con l'identificativo stabile della TV (sopravvive ai cambi di IP). */
    const val TXT_ID = "id"

    /** Porta TCP preferita del server: se occupata il server ripiega su una porta libera. */
    const val DEFAULT_PORT = 47810

    /** Porta UDP di beacon (TV → broadcast) e sonde di ricerca (telefono → broadcast). */
    const val BEACON_PORT = 47811

    /** Intervallo dei beacon periodici della TV. */
    const val BEACON_INTERVAL_MS = 2_000L

    /** Percorso dell'endpoint WebSocket. */
    const val WS_PATH = "/ws"

    /** Percorso dell'endpoint HTTP informativo (diagnostica dal browser). */
    const val INFO_PATH = "/api/info"

    /**
     * Pacchetto ZIP dei log della TV (dalla v1.3). Richiede il parametro [LOGS_ID_PARAM] con
     * l'identificativo della TV ricevuto nel saluto: solo un telefono già collegato lo conosce.
     */
    const val LOGS_PATH = "/api/logs"

    /** Parametro di [LOGS_PATH] con l'identificativo della TV. */
    const val LOGS_ID_PARAM = "id"

    /** Limite di risultati della ricerca ricorsiva: oltre, la lista diventa inutilizzabile. */
    const val MAX_SEARCH_RESULTS = 1_000

    /** Pacchetto dell'app TV: serve al telefono per farla aprire dal servizio di sistema della TV. */
    const val TV_PACKAGE = "com.icarocomix.icaromanagertv.tv"

    /**
     * Deep link che apre l'interfaccia della TV (v1.7). Lo usa il telefono tramite il servizio
     * di controllo remoto di Android TV, che lo apre anche se l'app TV non è mai stata avviata
     * o non è in esecuzione (TV appena riaccesa).
     */
    const val TV_APP_LINK = "icaromanagertv://open"

    /** Ripiego per lo stesso scopo: apertura dell'app tramite il Play Store della TV. */
    const val TV_STORE_LINK = "market://launch?id=$TV_PACKAGE"
}

/** Tipologia di una voce del file manager, con il codice usato sul filo. */
enum class EntryKind(val wire: String) {
    VOLUME("vol"),
    DIRECTORY("dir"),
    VIDEO("video"),
    AUDIO("audio"),
    IMAGE("image"),
    OTHER("file");

    val isContainer: Boolean get() = this == VOLUME || this == DIRECTORY

    companion object {
        fun fromWire(code: String?): EntryKind = entries.firstOrNull { it.wire == code } ?: OTHER
    }
}

/** Tasti del pad direzionale inviati dal telefono. */
enum class RemoteKey(val wire: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    OK("ok"),
    BACK("back");

    companion object {
        fun fromWire(code: String?): RemoteKey? = entries.firstOrNull { it.wire == code }
    }
}

/** Comandi di trasporto del player integrato. */
enum class PlayerAction(val wire: String) {
    PLAY_PAUSE("toggle"),
    STOP("stop"),
    /** Torna all'inizio del file corrente. */
    SEEK_START("start"),
    /** Salta agli ultimi secondi del file corrente (vedi `PlaybackController`). */
    SEEK_END("end"),
    REWIND("rew"),
    FORWARD("ffw"),
    PREVIOUS("prev"),
    NEXT("next"),

    // ---- Volume di sistema della TV (v1.4). Un server precedente li scarta come sconosciuti.

    /** Alza il volume di un passo (anche con volume fisso: la TV lo inoltra via HDMI-CEC). */
    VOLUME_UP("volup"),

    /** Abbassa il volume di un passo. */
    VOLUME_DOWN("voldown"),

    /** Attiva o disattiva l'audio. */
    MUTE("mute");

    /** Comando di volume: non riguarda il file in riproduzione. */
    val isVolume: Boolean get() = this == VOLUME_UP || this == VOLUME_DOWN || this == MUTE

    companion object {
        fun fromWire(code: String?): PlayerAction? = entries.firstOrNull { it.wire == code }
    }
}

/** Disposizione del player sullo schermo della TV. */
enum class TvView(val wire: String) {
    /** Video a tutto schermo con i controlli del player. */
    FULLSCREEN("full"),

    /** File manager visibile, video in una miniatura nell'angolo in basso a destra. */
    MINI("mini");

    companion object {
        fun fromWire(code: String?): TvView? = entries.firstOrNull { it.wire == code }
    }
}

/**
 * Comandi rivolti all'interfaccia della TV, non al file in riproduzione (v1.6). Un server
 * precedente li scarta come messaggi sconosciuti.
 */
enum class UiCommand(val wire: String) {
    /** Avvia l'app della TV, o la riporta in primo piano se è in background. */
    BRING_TO_FRONT("front"),

    /** Apre il pannello impostazioni del player della TV (traccia audio, velocità). */
    PLAYER_SETTINGS("settings"),

    /** Apre il selettore dei sottotitoli del player della TV. */
    PLAYER_SUBTITLES("subtitles"),

    /**
     * Tasto Home del pad (v1.7): schermata principale dell'app sulla TV, cioè app in primo piano,
     * video a schermo intero ridotto a miniatura (continua) ed elenco degli archivi senza ricerca.
     */
    HOME("home");

    /** Comando che agisce sul player a schermo intero (non solo sull'app). */
    val isPlayerPanel: Boolean get() = this == PLAYER_SETTINGS || this == PLAYER_SUBTITLES

    companion object {
        fun fromWire(code: String?): UiCommand? = entries.firstOrNull { it.wire == code }
    }
}
