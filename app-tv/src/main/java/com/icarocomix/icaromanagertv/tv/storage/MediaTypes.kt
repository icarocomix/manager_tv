package com.icarocomix.icaromanagertv.tv.storage

import android.webkit.MimeTypeMap
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import java.util.Locale

/**
 * MIME type e categoria dei file a partire dall'estensione. MimeTypeMap di Android non conosce
 * diversi formati comuni sui dischi multimediali (mkv su alcune versioni, ts/m2ts, flac, opus,
 * heic…): la tabella locale ha la precedenza e MimeTypeMap fa da ripiego.
 */
object MediaTypes {

    private val KNOWN = mapOf(
        // Video
        "mkv" to "video/x-matroska",
        "mk3d" to "video/x-matroska",
        "webm" to "video/webm",
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "vob" to "video/mpeg",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "3gp" to "video/3gpp",
        "ogv" to "video/ogg",
        "divx" to "video/divx",
        "rmvb" to "application/vnd.rn-realmedia-vbr",
        // Audio
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "wav" to "audio/x-wav",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/opus",
        "wma" to "audio/x-ms-wma",
        "ape" to "audio/x-ape",
        "alac" to "audio/mp4",
        "mka" to "audio/x-matroska",
        "dsf" to "audio/x-dsf",
        // Immagini
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        // Playlist e sottotitoli: aperti dai player come file generici.
        "m3u" to "audio/x-mpegurl",
        "m3u8" to "application/vnd.apple.mpegurl",
        "pls" to "audio/x-scpls",
        "srt" to "application/x-subrip",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
    )

    fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun mimeFor(name: String): String {
        val ext = extension(name)
        if (ext.isEmpty()) return GENERIC
        return KNOWN[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: GENERIC
    }

    /**
     * Categoria per icona e apertura. Se il provider SAF fornisce un MIME specifico lo preferisco,
     * altrimenti deduco dall'estensione (molti provider riportano application/octet-stream per mkv).
     */
    fun kindFor(name: String, providerMime: String? = null): EntryKind {
        val mime = providerMime?.takeIf { it != GENERIC && it.isNotBlank() } ?: mimeFor(name)
        return when {
            mime.startsWith("video/") || mime == "application/vnd.rn-realmedia-vbr" -> EntryKind.VIDEO
            mime.startsWith("audio/") -> EntryKind.AUDIO
            mime.startsWith("image/") -> EntryKind.IMAGE
            else -> EntryKind.OTHER
        }
    }

    /** Tipo generico della famiglia, usato come secondo tentativo se nessun player accetta quello specifico. */
    fun genericFor(kind: EntryKind): String? = when (kind) {
        EntryKind.VIDEO -> "video/*"
        EntryKind.AUDIO -> "audio/*"
        EntryKind.IMAGE -> "image/*"
        else -> null
    }

    /**
     * Vero se il file va al player integrato: video e audio, escluse le playlist testuali
     * (m3u/pls), classificate come audio dal MIME ma da lasciare ai player esterni.
     */
    fun isInternallyPlayable(kind: EntryKind, name: String): Boolean =
        (kind == EntryKind.VIDEO || kind == EntryKind.AUDIO) && extension(name) !in PLAYLIST_EXTENSIONS

    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls")

    const val GENERIC = "application/octet-stream"
}
