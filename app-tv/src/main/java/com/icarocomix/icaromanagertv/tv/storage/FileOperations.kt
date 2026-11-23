package com.icarocomix.icaromanagertv.tv.storage

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.util.Log
import com.icarocomix.icaromanagertv.common.files.FileNames
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import com.icarocomix.icaromanagertv.tv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Operazione sui file non riuscita, con un messaggio già pronto per l'utente. [needsFullAccess]
 * è vero quando la causa è l'«Accesso a tutti i file» non concesso: la UI della TV ne approfitta
 * per aprire subito la richiesta del permesso invece di limitarsi al messaggio.
 */
class FileOperationException(
    message: String,
    cause: Throwable? = null,
    val needsFullAccess: Boolean = false,
) : Exception(message, cause)

/** Voce degli appunti: il nodo e la cartella da cui proviene (serve agli spostamenti SAF). */
data class ClipItem(val node: Node, val parent: Node?)

/** Avanzamento di una copia o di uno spostamento. [totalBytes] ≤ 0 se ignoto. */
data class TransferProgress(val currentName: String, val doneBytes: Long, val totalBytes: Long) {
    val permille: Int get() = if (totalBytes <= 0) -1 else (doneBytes * 1000 / totalBytes).toInt().coerceIn(0, 1000)
}

/**
 * File in scrittura nella cartella della TV (caricamento dal telefono): [output] riceve i dati,
 * poi [commit] lo conferma o [abort] lo elimina. Il nome definitivo è [name].
 */
class PendingFile internal constructor(
    val name: String,
    val output: OutputStream,
    private val onAbort: () -> Unit,
) {
    private var closed = false

    fun commit() {
        if (closed) return
        closed = true
        output.close()
    }

    /** Chiude ed elimina il file parziale; non lancia mai eccezioni (si chiama nei percorsi d'errore). */
    fun abort() {
        if (closed) return
        closed = true
        runCatching { output.close() }
        runCatching(onAbort).onFailure { Log.w(TAG, "File parziale non eliminato: $name", it) }
    }

    private companion object {
        const val TAG = "PendingFile"
    }
}

/**
 * Scrittura sui dischi della TV (v1.9): crea cartelle, rinomina, elimina, copia e sposta file e
 * cartelle tra volumi POSIX e alberi SAF in qualunque combinazione.
 *
 * Tutte le funzioni sono bloccanti e vanno chiamate fuori dal main thread; quelle sospese
 * controllano la cancellazione a ogni blocco copiato, così un "Annulla" interrompe anche la copia
 * di un file da molti GB. Un file lasciato a metà da un errore o da un annullamento viene
 * eliminato: sul disco resta solo ciò che è stato copiato per intero.
 */
class FileOperations(private val context: Context, private val storage: StorageRepository) {

    private val resolver = context.contentResolver

    // ------------------------------------------------------------------ interrogazioni

    /** Vero se il nodo esiste ancora (cartelle della navigazione dopo un'operazione). */
    fun exists(node: Node): Boolean = when (val ref = node.ref) {
        is NodeRef.Posix -> ref.file.exists()
        is NodeRef.Saf -> runCatching {
            resolver.query(storage.documentUri(ref), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /** Nomi presenti nella cartella, nascosti compresi. */
    fun childNames(dir: Node): Set<String> = try {
        storage.listRaw(dir, includeHidden = true).mapTo(HashSet()) { it.name }
    } catch (e: StorageAccessException) {
        throw FileOperationException(e.message ?: context.getString(R.string.fop_err_read, dir.name), e)
    }

    /**
     * Vero se [node] coincide con [ancestor] o vi è contenuto: impedisce di copiare una cartella
     * dentro sé stessa (ricorsione senza fine che riempirebbe il disco).
     */
    fun isSameOrInside(node: Node, ancestor: Node): Boolean {
        val a = ancestor.ref
        val n = node.ref
        return when {
            a is NodeRef.Posix && n is NodeRef.Posix -> {
                val ap = runCatching { a.file.canonicalPath }.getOrDefault(a.file.absolutePath)
                val np = runCatching { n.file.canonicalPath }.getOrDefault(n.file.absolutePath)
                np == ap || np.startsWith("$ap/")
            }
            a is NodeRef.Saf && n is NodeRef.Saf && a.treeUri == n.treeUri -> {
                if (a.documentId == n.documentId) return true
                // Percorso del documento dalla radice dell'albero (API 26+): affidabile con ogni
                // provider che lo implementa. Ripiego sul prefisso degli id, che per il provider
                // dei dischi esterni ("XXXX-XXXX:cartella/sotto") rispecchia il percorso.
                val path = runCatching { DocumentsContract.findDocumentPath(resolver, storage.documentUri(n))?.path }.getOrNull()
                path?.contains(a.documentId) ?: n.documentId.startsWith("${a.documentId}/")
            }
            else -> false
        }
    }

    /** Flusso di lettura di un file (scaricamento sul telefono). */
    fun openRead(node: Node): InputStream = try {
        when (val ref = node.ref) {
            is NodeRef.Posix -> FileInputStream(ref.file)
            is NodeRef.Saf -> resolver.openInputStream(storage.documentUri(ref)) ?: throw FileNotFoundException(node.name)
        }
    } catch (e: IOException) {
        throw FileOperationException(context.getString(R.string.fop_err_read, node.name), e)
    } catch (e: SecurityException) {
        throw FileOperationException(context.getString(R.string.fop_err_read, node.name), e)
    }

    // ------------------------------------------------------------------ operazioni semplici

    /** Crea la cartella [name] (già validato) in [parent]; errore se il nome è già usato. */
    fun createDirectory(parent: Node, name: String): Node {
        ensureFree(parent, name)
        return writeGuard(parent) {
            when (val ref = parent.ref) {
                is NodeRef.Posix -> {
                    val dir = File(ref.file, name)
                    if (!dir.mkdir()) throw writeDenied(parent)
                    Node(name, EntryKind.DIRECTORY, NodeRef.Posix(dir), modified = dir.lastModified())
                }
                is NodeRef.Saf -> {
                    val uri = DocumentsContract.createDocument(resolver, storage.documentUri(ref), DocumentsContract.Document.MIME_TYPE_DIR, name)
                        ?: throw writeDenied(parent)
                    Node(name, EntryKind.DIRECTORY, NodeRef.Saf(ref.treeUri, DocumentsContract.getDocumentId(uri)))
                }
            }
        }
    }

    /** Rinomina [node] (contenuto in [parent]) in [newName], già validato. */
    fun rename(node: Node, parent: Node?, newName: String): Node {
        if (newName == node.name) return node
        // Cambia solo maiuscole/minuscole: su FAT/exFAT il nome "esiste già" (è lo stesso file),
        // quindi salto il controllo di unicità in quel caso.
        if (parent != null && !newName.equals(node.name, ignoreCase = true)) ensureFree(parent, newName)
        return writeGuard(parent ?: node) {
            when (val ref = node.ref) {
                is NodeRef.Posix -> {
                    val target = File(ref.file.parentFile, newName)
                    if (!ref.file.renameTo(target)) {
                        // Solo maiuscole su file system che non le distinguono: passo da un nome
                        // temporaneo, altrimenti renameTo può fallire o non cambiare nulla.
                        val temp = File(ref.file.parentFile, ".${newName}.${System.nanoTime()}")
                        if (!(ref.file.renameTo(temp) && temp.renameTo(target))) throw writeDenied(node)
                    }
                    node.copy(name = newName, ref = NodeRef.Posix(target))
                }
                is NodeRef.Saf -> {
                    val uri = DocumentsContract.renameDocument(resolver, storage.documentUri(ref), newName)
                    // null = rinominato mantenendo lo stesso id.
                    val id = uri?.let(DocumentsContract::getDocumentId) ?: ref.documentId
                    node.copy(name = newName, ref = NodeRef.Saf(ref.treeUri, id))
                }
            }
        }
    }

    /**
     * Elimina [node]; le cartelle con tutto il contenuto. [onProgress] riceve il numero di voci
     * eliminate (solo POSIX: SAF elimina una cartella in un'unica chiamata al provider).
     */
    suspend fun delete(node: Node, onProgress: (deleted: Int) -> Unit) {
        writeGuard(node) {
            when (val ref = node.ref) {
                is NodeRef.Posix -> {
                    var deleted = 0
                    // Dal basso verso l'alto: una cartella si elimina solo dopo il suo contenuto.
                    for (file in ref.file.walkBottomUp()) {
                        currentCoroutineContext().ensureActive()
                        if (!file.delete() && file.exists()) {
                            // Distinguo il permesso mancante o il disco in sola lettura dal
                            // generico "impossibile eliminare" (file in uso, nome non valido…).
                            throw posixWriteDenied(file) ?: FileOperationException(context.getString(R.string.fop_err_delete, file.name))
                        }
                        onProgress(++deleted)
                    }
                }
                is NodeRef.Saf -> if (!DocumentsContract.deleteDocument(resolver, storage.documentUri(ref))) {
                    throw FileOperationException(context.getString(R.string.fop_err_delete, node.name))
                }
            }
        }
    }

    // ------------------------------------------------------------------ copia e spostamento

    /**
     * Copia o sposta [items] nella cartella [dest]. In copia i nomi già usati diventano
     * "nome (1)"; uno spostamento nella stessa cartella non fa nulla. Gli spostamenti nello
     * stesso volume sono istantanei (rename POSIX o moveDocument SAF); tra volumi diversi
     * diventano copia + eliminazione dell'originale, solo a copia completata.
     *
     * @return nome dell'ultima voce scritta (per selezionarla nell'elenco), null se nessuna.
     */
    suspend fun transfer(items: List<ClipItem>, dest: Node, move: Boolean, onProgress: (TransferProgress) -> Unit): String? {
        for (item in items) {
            if (item.node.kind.isContainer && isSameOrInside(dest, item.node)) {
                throw FileOperationException(context.getString(R.string.fop_err_into_itself))
            }
            if (!exists(item.node)) throw FileOperationException(context.getString(R.string.fop_err_gone, item.node.name))
        }
        // Spostamenti "rapidi" prima di misurare: non copiano byte e non pesano sull'avanzamento.
        val pending = ArrayList<ClipItem>()
        var lastName: String? = null
        for (item in items) {
            if (move && item.parent != null && item.parent.ref == dest.ref) continue
            val fast = if (move) tryFastMove(item, dest) else null
            if (fast != null) lastName = fast else pending += item
        }
        if (pending.isEmpty()) return lastName

        val total = pending.sumOf { measure(it.node) }
        checkSpace(dest, total)
        val counter = ByteCounter(total, onProgress)
        for (item in pending) {
            currentCoroutineContext().ensureActive()
            val name = FileNames.unique(item.node.name, childNames(dest))
            copyNode(item.node, dest, name, counter)
            if (move) delete(item.node) {}
            lastName = name
        }
        return lastName
    }

    /** Spostamento senza copia dei dati, se possibile; null se serve copiare. */
    private fun tryFastMove(item: ClipItem, dest: Node): String? {
        val src = item.node.ref
        val dst = dest.ref
        // Il nome deve essere libero: rename e moveDocument non generano "nome (1)".
        if (item.node.name.lowercase() in childNames(dest).mapTo(HashSet()) { it.lowercase() }) return null
        return when {
            src is NodeRef.Posix && dst is NodeRef.Posix -> {
                // renameTo fallisce (false) tra file system diversi: in quel caso si copia.
                val target = File(dst.file, item.node.name)
                if (src.file.renameTo(target)) item.node.name else null
            }
            src is NodeRef.Saf && dst is NodeRef.Saf && src.treeUri == dst.treeUri -> {
                val parent = item.parent?.ref as? NodeRef.Saf ?: return null
                runCatching {
                    DocumentsContract.moveDocument(resolver, storage.documentUri(src), storage.documentUri(parent), storage.documentUri(dst))
                }.onFailure { Log.i(TAG, "moveDocument non disponibile, ripiego sulla copia: ${it.message}") }
                    .getOrNull()?.let { item.node.name }
            }
            else -> null
        }
    }

    /** Byte da copiare per [node] (cartelle: somma ricorsiva dei file). */
    private suspend fun measure(node: Node): Long {
        if (!node.kind.isContainer) return node.size.coerceAtLeast(0)
        var sum = 0L
        val children = runCatching { storage.listRaw(node, includeHidden = true) }.getOrElse { emptyList() }
        for (child in children) {
            currentCoroutineContext().ensureActive()
            sum += measure(child)
        }
        return sum
    }

    /** Spazio libero nella destinazione (solo POSIX: SAF non lo espone in modo affidabile). */
    private fun checkSpace(dest: Node, needed: Long) {
        val ref = dest.ref as? NodeRef.Posix ?: return
        val free = runCatching { StatFs(ref.file.absolutePath).availableBytes }.getOrNull() ?: return
        if (needed > free) {
            throw FileOperationException(
                context.getString(
                    R.string.fop_err_space,
                    Formatter.formatShortFileSize(context, needed),
                    Formatter.formatShortFileSize(context, free),
                ),
            )
        }
    }

    private suspend fun copyNode(node: Node, destDir: Node, name: String, counter: ByteCounter) {
        if (node.kind.isContainer) {
            val created = createDirectory(destDir, name)
            val children = try {
                storage.listRaw(node, includeHidden = true)
            } catch (e: StorageAccessException) {
                throw FileOperationException(e.message ?: context.getString(R.string.fop_err_read, node.name), e)
            }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                copyNode(child, created, child.name, counter)
            }
            return
        }
        counter.current = node.name
        val pendingFile = createFile(destDir, name, storage.mimeOf(node))
        try {
            openRead(node).use { input -> copyStream(input, pendingFile.output, counter) }
            pendingFile.commit()
        } catch (e: Throwable) {
            pendingFile.abort()
            if (e is IOException) throw FileOperationException(context.getString(R.string.fop_err_failed, node.name, e.message ?: e.javaClass.simpleName), e)
            throw e
        }
        // Conservo la data di modifica, utile per ordinare e riconoscere i file copiati.
        (destDir.ref as? NodeRef.Posix)?.let { if (node.modified > 0) File(it.file, name).setLastModified(node.modified) }
    }

    private suspend fun copyStream(input: InputStream, output: OutputStream, counter: ByteCounter) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            counter.add(read.toLong())
        }
        output.flush()
    }

    // ------------------------------------------------------------------ scrittura di un file

    /**
     * Crea un file vuoto [name] (già univoco) in [dir] e ne restituisce il flusso di scrittura.
     * Usato da copia e caricamento dal telefono: chi lo usa chiama commit o abort.
     */
    fun createFile(dir: Node, name: String, mime: String): PendingFile = writeGuard(dir) {
        when (val ref = dir.ref) {
            is NodeRef.Posix -> {
                val file = File(ref.file, name)
                val out = try {
                    FileOutputStream(file)
                } catch (e: FileNotFoundException) {
                    throw writeDenied(dir, e)
                }
                PendingFile(name, out.buffered(BUFFER_SIZE)) { file.delete() }
            }
            is NodeRef.Saf -> {
                val uri = DocumentsContract.createDocument(resolver, storage.documentUri(ref), mime, name) ?: throw writeDenied(dir)
                val out = resolver.openOutputStream(uri, "w") ?: run {
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                    throw writeDenied(dir)
                }
                // Il provider può aver cambiato il nome (es. estensione aggiunta dal MIME): lo rileggo.
                val actual = runCatching {
                    resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: name
                PendingFile(actual, out.buffered(BUFFER_SIZE)) { DocumentsContract.deleteDocument(resolver, uri) }
            }
        }
    }

    /** Come [createFile] ma con un nome libero calcolato qui: per il caricamento dal telefono. */
    fun createUniqueFile(dir: Node, requestedName: String, mime: String): PendingFile =
        createFile(dir, FileNames.unique(requestedName, childNames(dir)), mime)

    // ------------------------------------------------------------------ errori

    private fun ensureFree(parent: Node, name: String) {
        if (childNames(parent).any { it.equals(name, ignoreCase = true) }) {
            throw FileOperationException(context.getString(R.string.fop_err_exists, name))
        }
    }

    /**
     * Traduce i rifiuti di scrittura (permesso mancante, disco in sola lettura, albero SAF
     * autorizzato solo in lettura) in un messaggio che dice all'utente cosa fare.
     */
    private inline fun <T> writeGuard(target: Node, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        // È una IllegalStateException: senza questo ramo un "Annulla" diventerebbe un errore.
        throw e
    } catch (e: FileOperationException) {
        throw e
    } catch (e: SecurityException) {
        Log.w(TAG, "Scrittura negata su ${target.name}", e)
        throw writeDenied(target, e)
    } catch (e: IllegalStateException) {
        // Alcuni provider SAF segnalano così "documento non trovato" o "non supportato".
        Log.w(TAG, "Operazione rifiutata dal provider su ${target.name}", e)
        throw FileOperationException(context.getString(R.string.fop_err_failed, target.name, e.message ?: "provider"), e)
    } catch (e: UnsupportedOperationException) {
        Log.w(TAG, "Operazione non supportata su ${target.name}", e)
        throw FileOperationException(context.getString(R.string.fop_err_unsupported, target.name), e)
    } catch (e: IOException) {
        // createDocument/renameDocument SAF segnalano i fallimenti con FileNotFoundException.
        Log.w(TAG, "Errore di I/O su ${target.name}", e)
        throw FileOperationException(context.getString(R.string.fop_err_failed, target.name, e.message ?: e.javaClass.simpleName), e)
    }

    private fun writeDenied(target: Node, cause: Throwable? = null): FileOperationException = when (val ref = target.ref) {
        is NodeRef.Posix -> posixWriteDenied(ref.file, target.name, cause)
            ?: FileOperationException(
                context.getString(R.string.fop_err_write_other, target.name, cause?.message ?: context.getString(R.string.fop_err_write_unknown)),
                cause,
            )
        is NodeRef.Saf -> FileOperationException(context.getString(R.string.fop_err_write_saf, target.name), cause)
    }

    /**
     * Causa riconoscibile di un rifiuto di scrittura POSIX su [file], in ordine di priorità:
     * disco montato in sola lettura (es. NTFS su molte TV: nessun permesso lo rende scrivibile),
     * «Accesso a tutti i file» mancante (Android 11+: i soli permessi multimediali permettono di
     * leggere ma non di scrivere), permesso di scrittura mancante o disco rimovibile su Android
     * 9/10 (scrivibile solo tramite una cartella SAF). Null se nessuna di queste spiega l'errore.
     */
    private fun posixWriteDenied(file: File, name: String = file.name, cause: Throwable? = null): FileOperationException? = when {
        StorageAccess.isReadOnlyVolume(file) ->
            FileOperationException(context.getString(R.string.fop_err_write_readonly, name), cause)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !StorageAccess.hasFullAccess(context) ->
            FileOperationException(context.getString(R.string.fop_err_write_posix, name), cause, needsFullAccess = true)
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !StorageAccess.hasRuntime(context) ->
            FileOperationException(context.getString(R.string.fop_err_write_permission, name), cause, needsFullAccess = true)
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R && StorageAccess.isRemovable(file) ->
            FileOperationException(context.getString(R.string.fop_err_write_legacy, name), cause)
        else -> null
    }

    /** Contatore dei byte copiati che notifica l'avanzamento al massimo ogni [NOTIFY_INTERVAL_MS]. */
    private class ByteCounter(private val total: Long, private val onProgress: (TransferProgress) -> Unit) {
        var current: String = ""
        private var done = 0L
        private var lastNotify = 0L

        fun add(bytes: Long) {
            done += bytes
            val now = System.currentTimeMillis()
            if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                lastNotify = now
                onProgress(TransferProgress(current, done, total))
            }
        }
    }

    private companion object {
        const val TAG = "FileOperations"

        /** Blocchi grandi: le chiavette USB scrivono molto più velocemente a blocchi ampi. */
        const val BUFFER_SIZE = 256 * 1024
        const val NOTIFY_INTERVAL_MS = 250L
    }
}
