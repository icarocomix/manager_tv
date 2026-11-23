package com.icarocomix.icaromanagertv.common.protocol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Serializzazione JSON dei messaggi. Ogni messaggio è un oggetto con il campo `t` (tipo) e i
 * campi propri; i nomi sono brevi perché gli elenchi delle cartelle possono contare migliaia di
 * voci e viaggiano a ogni cambio di cartella.
 *
 * La decodifica non lancia mai eccezioni: un messaggio malformato o sconosciuto restituisce null
 * e viene ignorato dal chiamante, così un client di versione diversa non abbatte il server.
 */
object ProtocolCodec {

    // ------------------------------------------------------------------ server → client

    fun encode(message: ServerMessage): String = when (message) {
        is ServerMessage.Hello -> JSONObject()
            .put("t", "hello")
            .put("protocol", message.protocol)
            .put("name", message.name)
            .put("version", message.appVersion)
            .put("id", message.id)
        is ServerMessage.ListingMsg -> encodeListing(message.listing)
        is ServerMessage.StateMsg -> encodeState(message.state)
        is ServerMessage.Toast -> JSONObject().put("t", "toast").put("text", message.text)
        is ServerMessage.PlaybackMsg -> encodePlayback(message.playback)
        is ServerMessage.ProgressMsg -> encodeProgress(message.progress)
        is ServerMessage.StatusMsg -> encodeStatus(message.status)
        is ServerMessage.Error -> JSONObject().put("t", "error").put("text", message.text)
    }.toString()

    fun decodeServer(text: String): ServerMessage? = try {
        val o = JSONObject(text)
        when (o.optString("t")) {
            "hello" -> ServerMessage.Hello(
                protocol = o.getInt("protocol"),
                name = o.optString("name"),
                appVersion = o.optString("version"),
                id = o.optString("id"),
            )
            "listing" -> ServerMessage.ListingMsg(decodeListing(o))
            "state" -> ServerMessage.StateMsg(decodeState(o))
            "toast" -> ServerMessage.Toast(o.optString("text"))
            "playback" -> ServerMessage.PlaybackMsg(decodePlayback(o))
            "progress" -> ServerMessage.ProgressMsg(decodeProgress(o))
            "status" -> ServerMessage.StatusMsg(decodeStatus(o))
            "error" -> ServerMessage.Error(o.optString("text"))
            else -> null
        }
    } catch (_: JSONException) {
        null
    }

    private fun encodeListing(listing: Listing): JSONObject {
        val crumbs = JSONArray()
        listing.crumbs.forEach { crumbs.put(it) }
        // Ogni voce è un array posizionale [nome, tipo, dimensione, data, dettaglio]: rispetto a
        // un oggetto con chiavi dimezza circa il peso delle cartelle molto popolate.
        val entries = JSONArray()
        listing.entries.forEach { e ->
            val row = JSONArray().put(e.name).put(e.kind.wire).put(e.size).put(e.modified)
            if (e.detail != null) row.put(e.detail)
            entries.put(row)
        }
        return JSONObject()
            .put("t", "listing")
            .put("seq", listing.seq)
            .put("crumbs", crumbs)
            .put("entries", entries)
            .put("search", listing.searchResults)
            .put("complete", listing.complete)
    }

    private fun decodeListing(o: JSONObject): Listing {
        val crumbsJson = o.optJSONArray("crumbs") ?: JSONArray()
        val crumbs = List(crumbsJson.length()) { crumbsJson.optString(it) }
        val entriesJson = o.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<Entry>(entriesJson.length())
        for (i in 0 until entriesJson.length()) {
            val row = entriesJson.optJSONArray(i) ?: continue
            entries += Entry(
                name = row.optString(0),
                kind = EntryKind.fromWire(row.optString(1)),
                size = row.optLong(2, -1),
                modified = row.optLong(3, 0),
                detail = if (row.length() > 4 && !row.isNull(4)) row.optString(4) else null,
            )
        }
        return Listing(
            seq = o.getInt("seq"),
            crumbs = crumbs,
            entries = entries,
            searchResults = o.optBoolean("search", false),
            complete = o.optBoolean("complete", true),
        )
    }

    private fun encodeState(s: RemoteState): JSONObject = JSONObject()
        .put("t", "state")
        .put("seq", s.listingSeq)
        .put("query", s.query)
        .put("recursive", s.recursive)
        .put("searching", s.searching)
        .put("selected", s.selected)
        .putOpt("message", s.message)
        .putOpt("playing", s.nowPlaying)
        .put("top", s.scrollTop)

    private fun decodeState(o: JSONObject): RemoteState = RemoteState(
        listingSeq = o.optInt("seq"),
        query = o.optString("query"),
        recursive = o.optBoolean("recursive", true),
        searching = o.optBoolean("searching"),
        selected = o.optInt("selected", -1),
        message = if (o.isNull("message")) null else o.optString("message"),
        nowPlaying = if (o.isNull("playing")) null else o.optString("playing"),
        // Assente nei server precedenti alla v1.9: nessuno scroll indicato.
        scrollTop = o.optInt("top", -1).coerceAtLeast(-1),
    )

    private fun encodeStatus(s: TvStatus): JSONObject = JSONObject()
        .put("t", "status")
        .put("sync", s.synced)
        .put("clip", s.clipCount)
        .putOpt("clipName", s.clipName)
        .put("clipMove", s.clipMove)
        .putOpt("op", s.opText)
        .put("opP", s.opPermille)
        .put("opCancel", s.opCancelable)

    private fun decodeStatus(o: JSONObject): TvStatus = TvStatus(
        synced = o.optBoolean("sync", true),
        clipCount = o.optInt("clip", 0).coerceAtLeast(0),
        clipName = if (o.isNull("clipName")) null else o.optString("clipName"),
        clipMove = o.optBoolean("clipMove", false),
        opText = if (o.isNull("op")) null else o.optString("op"),
        // Fuori intervallo = non stimabile: la barra diventa indeterminata invece di sbagliare.
        opPermille = o.optInt("opP", -1).let { if (it in 0..1000) it else -1 },
        opCancelable = o.optBoolean("opCancel", false),
    )

    private fun encodePlayback(p: PlaybackState): JSONObject = JSONObject()
        .put("t", "playback")
        .put("active", p.active)
        .putOpt("title", p.title)
        .put("kind", p.kind.wire)
        .put("pos", p.positionMs)
        .put("dur", p.durationMs)
        .put("playing", p.playing)
        .put("buffering", p.buffering)
        .put("index", p.index)
        .put("count", p.count)
        .put("autoplay", p.autoplay)
        .put("view", p.view.wire)
        .put("resume", p.resumeFromMs)
        .put("vol", p.volume)
        .put("volMax", p.volumeMax)
        .put("mute", p.muted)
        .put("subs", p.subtitles)

    private fun decodePlayback(o: JSONObject): PlaybackState = PlaybackState(
        active = o.optBoolean("active"),
        title = if (o.isNull("title")) null else o.optString("title"),
        kind = EntryKind.fromWire(o.optString("kind")),
        positionMs = o.optLong("pos", 0).coerceAtLeast(0),
        durationMs = o.optLong("dur", -1),
        playing = o.optBoolean("playing"),
        buffering = o.optBoolean("buffering"),
        index = o.optInt("index", -1),
        count = o.optInt("count", 0).coerceAtLeast(0),
        autoplay = o.optBoolean("autoplay", true),
        view = TvView.fromWire(o.optString("view")) ?: TvView.FULLSCREEN,
        resumeFromMs = o.optLong("resume", -1),
        // Campi assenti (server precedente alla v1.4): volume ignoto, il telefono nasconde il cursore.
        volume = o.optInt("vol", -1),
        volumeMax = o.optInt("volMax", 0).coerceAtLeast(0),
        muted = o.optBoolean("mute", false),
        // Assente nei server precedenti alla v1.6: nessun sottotitolo noto.
        subtitles = o.optBoolean("subs", false),
    )

    private fun encodeProgress(p: ListingProgress): JSONObject {
        // Coppie appiattite [indice, millesimi, indice, millesimi, …]: il messaggio parte a ogni
        // salvataggio durante la riproduzione, conviene tenerlo minimo.
        val pairs = JSONArray()
        p.permille.forEach { (index, value) -> pairs.put(index).put(value) }
        return JSONObject().put("t", "progress").put("seq", p.seq).put("p", pairs)
    }

    private fun decodeProgress(o: JSONObject): ListingProgress {
        val pairs = o.optJSONArray("p") ?: JSONArray()
        val map = HashMap<Int, Int>(pairs.length() / 2)
        var i = 0
        while (i + 1 < pairs.length()) {
            val index = pairs.optInt(i, -1)
            val value = pairs.optInt(i + 1, -1)
            // Scarto coppie fuori intervallo invece di fidarmi del mittente.
            if (index >= 0 && value in 1..999) map[index] = value
            i += 2
        }
        return ListingProgress(seq = o.optInt("seq"), permille = map)
    }

    // ------------------------------------------------------------------ client → server

    fun encode(message: ClientMessage): String = when (message) {
        is ClientMessage.Hello -> JSONObject().put("t", "hello").put("protocol", message.protocol).put("name", message.name)
        is ClientMessage.Open -> JSONObject().put("t", "open").put("seq", message.seq).put("index", message.index)
        is ClientMessage.Select -> JSONObject().put("t", "select").put("seq", message.seq).put("index", message.index)
        ClientMessage.Up -> JSONObject().put("t", "up")
        ClientMessage.Root -> JSONObject().put("t", "root")
        is ClientMessage.Crumb -> JSONObject().put("t", "crumb").put("depth", message.depth)
        is ClientMessage.Query -> JSONObject().put("t", "query").put("text", message.text)
        is ClientMessage.Recursive -> JSONObject().put("t", "recursive").put("on", message.enabled)
        is ClientMessage.Key -> JSONObject().put("t", "key").put("key", message.key.wire)
        ClientMessage.Refresh -> JSONObject().put("t", "refresh")
        is ClientMessage.Player -> JSONObject().put("t", "player").put("a", message.action.wire)
        is ClientMessage.SeekTo -> JSONObject().put("t", "seek").put("pos", message.positionMs)
        is ClientMessage.Autoplay -> JSONObject().put("t", "autoplay").put("on", message.enabled)
        is ClientMessage.Resume -> JSONObject().put("t", "resume").put("saved", message.fromSaved)
        is ClientMessage.Layout -> JSONObject().put("t", "view").put("view", message.view.wire)
        is ClientMessage.Volume -> JSONObject().put("t", "volume").put("level", message.level)
        is ClientMessage.Ui -> JSONObject().put("t", "ui").put("cmd", message.command.wire)
        is ClientMessage.Sync -> JSONObject().put("t", "sync").put("on", message.enabled)
        is ClientMessage.Scroll -> JSONObject().put("t", "scroll").put("seq", message.seq).put("index", message.index)
        is ClientMessage.FileOp -> JSONObject()
            .put("t", "fop")
            .put("op", message.op.wire)
            .put("seq", message.seq)
            .put("index", message.index)
            .putOpt("name", message.name)
    }.toString()

    fun decodeClient(text: String): ClientMessage? = try {
        val o = JSONObject(text)
        when (o.optString("t")) {
            "hello" -> ClientMessage.Hello(o.getInt("protocol"), o.optString("name"))
            "open" -> ClientMessage.Open(o.getInt("seq"), o.getInt("index"))
            "select" -> ClientMessage.Select(o.getInt("seq"), o.getInt("index"))
            "up" -> ClientMessage.Up
            "root" -> ClientMessage.Root
            "crumb" -> ClientMessage.Crumb(o.getInt("depth"))
            // Limito la lunghezza della query: è testo digitato, nessun motivo per accettarne di più.
            "query" -> ClientMessage.Query(o.optString("text").take(MAX_QUERY_LENGTH))
            "recursive" -> ClientMessage.Recursive(o.optBoolean("on"))
            "key" -> RemoteKey.fromWire(o.optString("key"))?.let { ClientMessage.Key(it) }
            "refresh" -> ClientMessage.Refresh
            "player" -> PlayerAction.fromWire(o.optString("a"))?.let { ClientMessage.Player(it) }
            // Posizione negativa senza senso: la riporto a zero invece di scartare il comando.
            "seek" -> ClientMessage.SeekTo(o.getLong("pos").coerceAtLeast(0))
            "autoplay" -> ClientMessage.Autoplay(o.getBoolean("on"))
            "resume" -> ClientMessage.Resume(o.getBoolean("saved"))
            "view" -> TvView.fromWire(o.optString("view"))?.let { ClientMessage.Layout(it) }
            // Il limite superiore dipende dalla TV: lo applica il server, qui escludo i negativi.
            "volume" -> ClientMessage.Volume(o.getInt("level").coerceAtLeast(0))
            "ui" -> UiCommand.fromWire(o.optString("cmd"))?.let { ClientMessage.Ui(it) }
            "sync" -> ClientMessage.Sync(o.getBoolean("on"))
            "scroll" -> ClientMessage.Scroll(o.getInt("seq"), o.getInt("index").coerceAtLeast(0))
            "fop" -> decodeFileOp(o)
            else -> null
        }
    } catch (_: JSONException) {
        null
    }

    /**
     * Operazione sui file: scarto subito quelle incomplete (voce o nome mancanti) invece di
     * lasciarle arrivare al controller. Il nome viene validato davvero sulla TV ([com.icarocomix.icaromanagertv.common.files.FileNames]).
     */
    private fun decodeFileOp(o: JSONObject): ClientMessage.FileOp? {
        val op = FileOpKind.fromWire(o.optString("op")) ?: return null
        val seq = o.optInt("seq", -1)
        val index = o.optInt("index", -1)
        val name = if (o.isNull("name")) null else o.optString("name").take(MAX_NAME_LENGTH)
        if (op.needsEntry && (seq < 0 || index < 0)) return null
        if (op.needsName && name.isNullOrBlank()) return null
        return ClientMessage.FileOp(op, seq, index, name)
    }

    const val MAX_QUERY_LENGTH = 200

    /** Più lungo di qualunque nome valido (255 byte UTF-8): il taglio vero lo fa la validazione. */
    const val MAX_NAME_LENGTH = 512
}
