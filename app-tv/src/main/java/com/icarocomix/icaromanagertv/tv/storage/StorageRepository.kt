package com.icarocomix.icaromanagertv.tv.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.util.Log
import androidx.core.content.FileProvider
import com.icarocomix.icaromanagertv.common.protocol.Entry
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import com.icarocomix.icaromanagertv.common.search.NaturalOrder
import com.icarocomix.icaromanagertv.common.search.SearchText
import com.icarocomix.icaromanagertv.tv.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Riferimento interno a un nodo: percorso POSIX o documento di un albero SAF. Mai esposto ai client. */
sealed class NodeRef {
    data class Posix(val file: File) : NodeRef()
    data class Saf(val treeUri: Uri, val documentId: String) : NodeRef()
}

/** Volume, cartella o file con il riferimento per raggiungerlo. */
data class Node(
    val name: String,
    val kind: EntryKind,
    val ref: NodeRef,
    val size: Long = -1,
    val modified: Long = 0,
    val detail: String? = null,
    val providerMime: String? = null,
) {
    fun toEntry(detailOverride: String? = detail): Entry = Entry(name, kind, size, modified, detailOverride)
}

/** Risultato di una ricerca ricorsiva: il nodo e la catena di cartelle dalla radice della ricerca. */
data class SearchHit(val node: Node, val ancestors: List<Node>)

/** Cartella non leggibile (permesso mancante, disco rimosso o non pronto). */
class StorageAccessException(message: String) : Exception(message)

/**
 * Accesso ai volumi di archiviazione: dischi USB e memoria interna come percorsi POSIX (via
 * StorageManager), più gli alberi SAF che l'utente ha autorizzato. I due accessi convivono perché
 * nessuno basta da solo su tutte le TV: alcune non hanno l'impostazione "accesso a tutti i file",
 * altre non hanno il selettore di documenti di sistema (DocumentsUI).
 */
class StorageRepository(private val context: Context) {

    private val resolver = context.contentResolver

    // ------------------------------------------------------------------ volumi

    fun volumes(): List<Node> {
        val out = ArrayList<Node>()
        val seen = HashSet<String>()
        val sm = context.getSystemService(StorageManager::class.java)
        sm?.storageVolumes.orEmpty().forEach { volume ->
            val state = volume.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
            val dir = volumeDirectory(volume) ?: return@forEach
            if (!seen.add(dir.absolutePath)) return@forEach
            val kindLabel = context.getString(if (volume.isRemovable) R.string.volume_usb else R.string.volume_internal)
            out += Node(
                name = volume.getDescription(context) ?: dir.name,
                kind = EntryKind.VOLUME,
                ref = NodeRef.Posix(dir),
                detail = listOfNotNull(kindLabel, spaceText(dir)).joinToString("  ·  "),
            )
        }
        // Alcuni box montano i dischi fuori da StorageManager (es. /mnt/usb/sda1): li cerco nei
        // punti di montaggio noti, solo se leggibili e non già elencati.
        MOUNT_ROOTS.forEach { root ->
            File(root).listFiles().orEmpty()
                .filter { it.isDirectory && it.canRead() && it.name !in IGNORED_MOUNTS && seen.add(it.absolutePath) }
                .forEach { dir ->
                    out += Node(
                        name = dir.name,
                        kind = EntryKind.VOLUME,
                        ref = NodeRef.Posix(dir),
                        detail = listOfNotNull(context.getString(R.string.volume_usb), spaceText(dir)).joinToString("  ·  "),
                    )
                }
        }
        // Alberi SAF autorizzati in modo persistente dall'utente.
        resolver.persistedUriPermissions.filter { it.isReadPermission }.forEach { perm ->
            val tree = perm.uri
            val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return@forEach
            val name = queryDisplayName(DocumentsContract.buildDocumentUriUsingTree(tree, docId)) ?: docId
            out += Node(
                name = name,
                kind = EntryKind.VOLUME,
                ref = NodeRef.Saf(tree, docId),
                detail = context.getString(R.string.volume_saf),
            )
        }
        return out
    }

    /** Percorso del volume: API pubblica da Android 11, metodo nascosto (consentito) prima. */
    private fun volumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        return runCatching {
            val path = StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
            path?.let(::File)
        }.getOrNull()
    }

    private fun spaceText(dir: File): String? = runCatching {
        val stat = StatFs(dir.absolutePath)
        context.getString(
            R.string.volume_space,
            Formatter.formatShortFileSize(context, stat.availableBytes),
            Formatter.formatShortFileSize(context, stat.totalBytes),
        )
    }.getOrNull()

    // ------------------------------------------------------------------ elenco

    /** Contenuto ordinato di una cartella o di un volume: prima le cartelle, poi i file, ordine naturale. */
    fun list(dir: Node): List<Node> {
        val children = when (val ref = dir.ref) {
            is NodeRef.Posix -> listPosix(ref.file)
            is NodeRef.Saf -> listSaf(ref.treeUri, ref.documentId)
        }
        return children.sortedWith(compareBy<Node> { !it.kind.isContainer }.thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) })
    }

    private fun listPosix(dir: File): List<Node> {
        // listFiles() restituisce null sia senza permesso sia con disco non pronto: in entrambi i
        // casi lo segnalo invece di mostrare una cartella vuota fuorviante.
        val files = dir.listFiles()
            ?: throw StorageAccessException(context.getString(R.string.error_unreadable, dir.absolutePath))
        return files.asSequence()
            .filter { !it.name.startsWith(".") }
            .map { f ->
                val isDir = f.isDirectory
                Node(
                    name = f.name,
                    kind = if (isDir) EntryKind.DIRECTORY else MediaTypes.kindFor(f.name),
                    ref = NodeRef.Posix(f),
                    size = if (isDir) -1 else f.length(),
                    modified = f.lastModified(),
                )
            }
            .toList()
    }

    private fun listSaf(tree: Uri, documentId: String): List<Node> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val out = ArrayList<Node>()
        try {
            resolver.query(childrenUri, SAF_COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Node(
                        name = name,
                        kind = if (isDir) EntryKind.DIRECTORY else MediaTypes.kindFor(name, mime),
                        ref = NodeRef.Saf(tree, id),
                        size = if (isDir || c.isNull(3)) -1 else c.getLong(3),
                        modified = if (c.isNull(4)) 0 else c.getLong(4),
                        providerMime = mime,
                    )
                }
            } ?: throw StorageAccessException(context.getString(R.string.error_unreadable, tree.toString()))
        } catch (e: SecurityException) {
            // Permesso SAF revocato o disco scollegato: il provider lancia SecurityException.
            Log.w(TAG, "Albero SAF non accessibile: $tree", e)
            throw StorageAccessException(context.getString(R.string.error_saf_revoked))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Documento SAF non valido: $documentId", e)
            throw StorageAccessException(context.getString(R.string.error_unreadable, documentId))
        }
        return out
    }

    private fun queryDisplayName(documentUri: Uri): String? = runCatching {
        resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------------ ricerca

    /**
     * Ricerca ricorsiva per nome a partire da [roots], in ampiezza: i risultati vicini alla
     * cartella corrente arrivano per primi. [onBatch] riceve i risultati a blocchi, così la lista
     * si popola mentre la scansione prosegue su un disco lento. Si interrompe alla cancellazione
     * della coroutine o al raggiungimento di [limit] risultati.
     */
    suspend fun search(
        roots: List<Node>,
        query: String,
        limit: Int,
        onBatch: suspend (List<SearchHit>) -> Unit,
    ) {
        val tokens = SearchText.tokens(query)
        if (tokens.isEmpty()) return
        val queue = ArrayDeque<Pair<Node, List<Node>>>()
        roots.forEach { queue.addLast(it to emptyList()) }
        val batch = ArrayList<SearchHit>()
        var found = 0
        var lastFlush = System.currentTimeMillis()
        while (queue.isNotEmpty() && found < limit) {
            currentCoroutineContext().ensureActive()
            val (dir, ancestors) = queue.removeFirst()
            // Le cartelle illeggibili (es. di sistema) non interrompono la ricerca: le salto.
            val children = runCatching { list(dir) }.getOrElse { emptyList() }
            val chain = ancestors + dir
            for (child in children) {
                if (SearchText.matches(child.name, tokens)) {
                    batch += SearchHit(child, chain)
                    found++
                    if (found >= limit) break
                }
                if (child.kind.isContainer && chain.size < MAX_DEPTH) queue.addLast(child to chain)
            }
            val now = System.currentTimeMillis()
            if (batch.isNotEmpty() && (batch.size >= BATCH_SIZE || now - lastFlush >= BATCH_INTERVAL_MS)) {
                onBatch(batch.toList())
                batch.clear()
                lastFlush = now
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    // ------------------------------------------------------------------ apertura

    /** URI content:// da passare al player, leggibile grazie al permesso temporaneo dell'Intent. */
    fun openUri(node: Node): Uri = when (val ref = node.ref) {
        is NodeRef.Posix -> FileProvider.getUriForFile(context, "${context.packageName}.files", ref.file)
        is NodeRef.Saf -> DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, ref.documentId)
    }

    fun mimeOf(node: Node): String =
        node.providerMime?.takeIf { it != MediaTypes.GENERIC && it.isNotBlank() } ?: MediaTypes.mimeFor(node.name)

    /** Vero se il nodo (o un suo antenato) si trova nel volume indicato: serve allo smontaggio USB. */
    fun isUnder(node: Node, mountPath: String): Boolean {
        val ref = node.ref as? NodeRef.Posix ?: return false
        return ref.file.absolutePath == mountPath || ref.file.absolutePath.startsWith("$mountPath/")
    }

    private companion object {
        const val TAG = "Storage"
        const val MAX_DEPTH = 32
        const val BATCH_SIZE = 40
        const val BATCH_INTERVAL_MS = 300L
        val MOUNT_ROOTS = listOf("/mnt/usb", "/mnt/usbhost", "/storage/usb")
        val IGNORED_MOUNTS = setOf("emulated", "self")
        val SAF_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
