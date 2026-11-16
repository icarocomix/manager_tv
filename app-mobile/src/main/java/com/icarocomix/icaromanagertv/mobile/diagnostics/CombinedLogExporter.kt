package com.icarocomix.icaromanagertv.mobile.diagnostics

import android.content.Context
import android.net.Uri
import android.util.Log
import com.icarocomix.icaromanagertv.common.log.AppLogs
import com.icarocomix.icaromanagertv.mobile.client.Connection
import com.icarocomix.icaromanagertv.mobile.client.RemoteClient
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pacchetto unico di diagnostica dal telefono: log della TV (scaricati via HTTP dal server
 * della TV) in `tv/` e log del telefono in `telefono/`, nello stesso ZIP. Un problema di
 * controllo remoto coinvolge quasi sempre entrambi i lati: averli insieme, con gli stessi orari,
 * evita di doverli recuperare e allineare a mano.
 *
 * Se la TV non risponde (non collegata, app vecchia) salvo comunque i log del telefono con una
 * nota sul motivo in `tv/NON-DISPONIBILE.txt`: anche quel motivo è un dato diagnostico.
 */
class CombinedLogExporter(context: Context, private val client: RemoteClient) {

    private val app = context.applicationContext

    /** Esito dell'esportazione: [tvError] è null se i log della TV sono inclusi. */
    data class Result(val tvError: String?)

    /** Scrive il pacchetto in [destination] (documento scelto dall'utente). Bloccante: fuori dal main. */
    fun exportTo(destination: Uri): Result {
        val tvZip = File.createTempFile("tv-logs-", ".zip", app.cacheDir)
        try {
            val tvError = try {
                client.downloadTvLogs(tvZip)
                null
            } catch (e: IOException) {
                Log.w(TAG, "Log della TV non scaricati", e)
                e.message ?: e.javaClass.simpleName
            }
            val out = app.contentResolver.openOutputStream(destination)
                ?: throw IOException("Il file scelto non è scrivibile")
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                if (tvError == null) {
                    copyEntries(tvZip, zip, TV_PREFIX)
                } else {
                    zip.putNextEntry(ZipEntry(TV_PREFIX + "NON-DISPONIBILE.txt"))
                    zip.write("Log della TV non inclusi: $tvError\n".toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                AppLogs.writeEntries(app, zip, PHONE_PREFIX, connectionInfo())
            }
            Log.i(TAG, "Pacchetto di log salvato (TV ${if (tvError == null) "inclusa" else "assente"})")
            return Result(tvError)
        } finally {
            tvZip.delete()
        }
    }

    /**
     * Ricopio le voci dello ZIP della TV sotto [prefix]. Ignoro nomi con `..` o assoluti: il
     * contenuto arriva dalla rete e non deve poter uscire dalla propria cartella nel pacchetto.
     */
    private fun copyEntries(source: File, zip: ZipOutputStream, prefix: String) {
        ZipInputStream(FileInputStream(source)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name.startsWith("/") || name.split('/').any { it == ".." }) continue
                zip.putNextEntry(ZipEntry(prefix + name).apply { if (entry.time > 0) time = entry.time })
                input.copyTo(zip)
                zip.closeEntry()
            }
        }
    }

    private fun connectionInfo(): Map<String, String> {
        val description = when (val c = client.connection.value) {
            is Connection.Connected -> "collegato a «${c.serverName}» (${c.tv.host}:${c.tv.port}), app TV ${c.serverVersion}"
            is Connection.Connecting -> "collegamento in corso a ${c.tv.host}:${c.tv.port}"
            is Connection.Retrying -> "nuovo tentativo verso ${c.tv.host}:${c.tv.port} tra ${c.delayMs} ms"
            is Connection.Failed -> "errore con ${c.tv.host}:${c.tv.port}: ${c.reason}"
            Connection.Searching -> "ricerca della TV in corso"
        }
        return linkedMapOf(
            "Collegamento" to description,
            "TV trovate" to client.discovery.endpoints.value.values.joinToString { "${it.name} (${it.host}:${it.port})" }.ifEmpty { "nessuna" },
        )
    }

    private companion object {
        const val TAG = "CombinedLogExporter"
        const val TV_PREFIX = "tv/"
        const val PHONE_PREFIX = "telefono/"
    }
}
