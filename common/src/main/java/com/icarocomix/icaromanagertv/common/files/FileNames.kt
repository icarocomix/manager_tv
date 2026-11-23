package com.icarocomix.icaromanagertv.common.files

/**
 * Regole sui nomi di file e cartelle condivise da TV e telefono: il telefono le usa per
 * segnalare subito un nome non valido, la TV le riapplica comunque prima di scrivere sul disco
 * (un client non è mai affidabile).
 */
object FileNames {

    /** Limite dei file system usati sui dischi USB (FAT32/exFAT/NTFS/ext4): 255 byte UTF-8. */
    const val MAX_BYTES = 255

    /**
     * Caratteri vietati: '/' separa i percorsi ovunque; gli altri sono rifiutati da FAT32/exFAT,
     * il formato tipico delle chiavette, e creerebbero file illeggibili da un PC Windows.
     */
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** Esito della validazione: [name] normalizzato se valido, altrimenti il motivo in [error]. */
    sealed class Check {
        data class Valid(val name: String) : Check()
        data class Invalid(val error: Problem) : Check()
    }

    /** Motivi di rifiuto, tradotti in testo dall'interfaccia. */
    enum class Problem { EMPTY, RESERVED, FORBIDDEN_CHAR, TOO_LONG }

    /**
     * Valida un nome digitato. Tolgo spazi iniziali e finali (e i punti finali, che Windows
     * scarta in silenzio rendendo il file irraggiungibile); rifiuto caratteri di controllo,
     * caratteri vietati, "." e ".." e nomi troppo lunghi.
     */
    fun check(raw: String): Check {
        val trimmed = raw.trim()
        // "." e ".." prima di togliere i punti finali, che li ridurrebbero a un nome vuoto.
        if (trimmed == "." || trimmed == "..") return Check.Invalid(Problem.RESERVED)
        val name = trimmed.trimEnd('.', ' ')
        if (name.isEmpty()) return Check.Invalid(Problem.EMPTY)
        if (name.any { it < ' ' || it == '\u007f' || it in FORBIDDEN }) return Check.Invalid(Problem.FORBIDDEN_CHAR)
        if (name.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Check.Invalid(Problem.TOO_LONG)
        return Check.Valid(name)
    }

    /** Nome valido normalizzato, o null. */
    fun validOrNull(raw: String): String? = (check(raw) as? Check.Valid)?.name

    /**
     * Nome libero nella cartella che contiene già [existing]: [name] stesso se libero, altrimenti
     * "base (1).ext", "base (2).ext"… come i file manager più diffusi. Il confronto ignora
     * maiuscole e minuscole perché FAT32/exFAT/NTFS non le distinguono: "Film.mkv" e "film.mkv"
     * sarebbero lo stesso file.
     */
    fun unique(name: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in taken) return name
        val (base, ext) = split(name)
        // Se il nome è già "Film (3)", riparto da "Film" e cerco il primo numero libero.
        val root = COPY_SUFFIX.find(base)?.let { base.substring(0, it.range.first) } ?: base
        var n = 1
        while (true) {
            val candidate = "$root ($n)$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    /**
     * Separa base ed estensione. I file nascosti (".config") e i nomi senza punto non hanno
     * estensione; le doppie estensioni comuni (".tar.gz") restano unite per non generare
     * "archivio.tar (1).gz".
     */
    fun split(name: String): Pair<String, String> {
        val lower = name.lowercase()
        DOUBLE_EXTENSIONS.firstOrNull { lower.endsWith(it) && name.length > it.length }?.let {
            return name.substring(0, name.length - it.length) to name.substring(name.length - it.length)
        }
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    private val COPY_SUFFIX = Regex(" \\(\\d+\\)$")
    private val DOUBLE_EXTENSIONS = listOf(".tar.gz", ".tar.bz2", ".tar.xz")
}
