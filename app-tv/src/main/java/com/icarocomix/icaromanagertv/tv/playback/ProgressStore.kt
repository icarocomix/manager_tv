package com.icarocomix.icaromanagertv.tv.playback

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.icarocomix.icaromanagertv.common.protocol.ListingProgress
import com.icarocomix.icaromanagertv.tv.storage.Node
import com.icarocomix.icaromanagertv.tv.storage.NodeRef
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Posizione di riproduzione salvata per un file. [sizeBytes] serve a riconoscere un file
 * sostituito con lo stesso nome: in quel caso la posizione salvata non ha più senso.
 */
data class SavedProgress(val positionMs: Long, val durationMs: Long, val sizeBytes: Long, val updatedAt: Long)

/** Posizione di un file appena salvata ([permille] ≥ 1) o rimossa ([permille] = -1). */
data class ProgressChange(val key: String, val permille: Int)

/**
 * Archivio locale delle posizioni di riproduzione, una riga per file. SQLite con SQL scritto a
 * mano (niente Room): una sola tabella con chiave primaria testuale, accessi puntuali per chiave.
 *
 * Tutti i metodi fanno I/O su disco: vanno chiamati fuori dal thread principale.
 */
class ProgressStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    // Notifico ogni salvataggio o cancellazione: l'elenco aggiorna la barra della sola riga
    // interessata senza rileggere il database. Se chi ascolta resta indietro perdo le notifiche
    // più vecchie, mai il salvataggio (che è già su disco).
    private val _changes = MutableSharedFlow<ProgressChange>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<ProgressChange> = _changes.asSharedFlow()

    override fun onConfigure(db: SQLiteDatabase) {
        // WAL: le scritture periodiche durante la riproduzione non bloccano le letture.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS progress (
                media_key   TEXT    PRIMARY KEY NOT NULL,
                name        TEXT    NOT NULL,
                position_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                size_bytes  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        // Indice per la potatura delle voci più vecchie.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_progress_updated ON progress(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Prima versione dello schema: nessuna migrazione da applicare.
    }

    /** Posizione salvata per il nodo, null se assente o se il file risulta cambiato. */
    fun load(node: Node): SavedProgress? = guard("lettura", null) {
        readableDatabase.rawQuery(
            "SELECT position_ms, duration_ms, size_bytes, updated_at FROM progress WHERE media_key = ?",
            arrayOf(keyOf(node)),
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val saved = SavedProgress(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3))
            // Dimensione diversa = file sostituito: la vecchia posizione non vale più.
            if (node.size > 0 && saved.sizeBytes > 0 && node.size != saved.sizeBytes) null else saved
        }
    }

    /**
     * Avanzamento in millesimi dei nodi indicati, per chiave ([keyOf]). Una sola query per gruppo
     * di chiavi (IN con parametri) invece di una per file; assenti i nodi senza posizione, quelli
     * con durata ignota e quelli sostituiti (dimensione diversa).
     */
    fun loadPermille(nodes: List<Node>): Map<String, Int> {
        if (nodes.isEmpty()) return emptyMap()
        val byKey = nodes.associateBy(::keyOf)
        val result = HashMap<String, Int>()
        guard("lettura in blocco", Unit) {
            // Resto sotto il limite storico di SQLite di 999 parametri per istruzione.
            for (chunk in byKey.keys.chunked(MAX_SQL_PARAMS)) {
                val placeholders = chunk.joinToString(",") { "?" }
                readableDatabase.rawQuery(
                    "SELECT media_key, position_ms, duration_ms, size_bytes FROM progress WHERE media_key IN ($placeholders)",
                    chunk.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        val key = c.getString(0)
                        val node = byKey[key] ?: continue
                        val sizeBytes = c.getLong(3)
                        if (node.size > 0 && sizeBytes > 0 && node.size != sizeBytes) continue
                        val permille = ListingProgress.permilleOf(c.getLong(1), c.getLong(2))
                        if (permille > 0) result[key] = permille
                    }
                }
            }
        }
        return result
    }

    /**
     * Registra la posizione. Applica le regole di significatività: troppo vicino all'inizio o
     * alla fine non c'è nulla da riprendere, quindi la voce viene rimossa.
     */
    fun save(node: Node, positionMs: Long, durationMs: Long) {
        if (!isWorthResuming(positionMs, durationMs)) {
            clear(node)
            return
        }
        guard("scrittura", Unit) {
            val values = ContentValues().apply {
                put("media_key", keyOf(node))
                put("name", node.name)
                put("position_ms", positionMs)
                put("duration_ms", durationMs)
                put("size_bytes", node.size)
                put("updated_at", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("progress", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            _changes.tryEmit(ProgressChange(keyOf(node), ListingProgress.permilleOf(positionMs, durationMs)))
            Unit
        }
    }

    fun clear(node: Node) {
        guard("cancellazione", Unit) {
            writableDatabase.execSQL("DELETE FROM progress WHERE media_key = ?", arrayOf(keyOf(node)))
            _changes.tryEmit(ProgressChange(keyOf(node), -1))
        }
    }

    /** Tiene solo le [MAX_ROWS] voci usate più di recente: l'archivio non cresce all'infinito. */
    fun prune() {
        guard("potatura", Unit) {
            writableDatabase.execSQL(
                "DELETE FROM progress WHERE media_key NOT IN (SELECT media_key FROM progress ORDER BY updated_at DESC LIMIT $MAX_ROWS)",
            )
        }
    }

    /**
     * Un errore del database (disco interno pieno, file corrotto) non deve fermare la
     * riproduzione: lo registro e proseguo senza la funzione di ripresa.
     */
    private inline fun <T> guard(operation: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: SQLiteException) {
        Log.e(TAG, "Errore SQLite in $operation delle posizioni", e)
        fallback
    }

    companion object {
        private const val TAG = "ProgressStore"
        private const val DB_NAME = "playback.db"
        private const val DB_VERSION = 1
        private const val MAX_ROWS = 5_000
        private const val MAX_SQL_PARAMS = 500

        /** Sotto questa posizione non propongo la ripresa: è appena iniziato. */
        const val MIN_RESUME_MS = 15_000L

        /** Negli ultimi secondi (o oltre il 95%) il file è da considerare finito. */
        const val END_MARGIN_MS = 30_000L
        private const val END_RATIO = 0.95

        fun isWorthResuming(positionMs: Long, durationMs: Long): Boolean {
            if (positionMs < MIN_RESUME_MS) return false
            if (durationMs <= 0) return true
            return positionMs < durationMs - END_MARGIN_MS && positionMs < durationMs * END_RATIO
        }

        /**
         * Chiave stabile del file: percorso assoluto per i volumi POSIX, albero + documento per
         * SAF. Gli stessi file raggiunti dalla ricerca o dalla cartella hanno la stessa chiave.
         */
        fun keyOf(node: Node): String = when (val ref = node.ref) {
            is NodeRef.Posix -> "file:" + ref.file.absolutePath
            is NodeRef.Saf -> "saf:" + ref.treeUri + "#" + ref.documentId
        }
    }
}
