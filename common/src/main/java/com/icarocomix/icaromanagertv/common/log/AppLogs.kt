package com.icarocomix.icaromanagertv.common.log

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.icarocomix.icaromanagertv.common.ui.CreditsDialog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Log dell'app su file, esportabili come pacchetto ZIP (TV e telefono usano lo stesso codice).
 *
 * Invece di sostituire ogni chiamata a [Log] con un logger proprio, all'avvio del processo lancio
 * un `logcat --pid=<pid> -f <file>` con rotazione: cattura così anche i log di ExoPlayer,
 * NanoHTTPD, OkHttp e del runtime (eccezioni non gestite), con lo stesso formato che si
 * vedrebbe con `adb logcat`. Un'app può leggere i log del proprio processo senza permessi
 * speciali (da Android 4.1 logd filtra per uid). Il processo logcat è figlio dell'app e muore
 * con lei: Android termina l'intero gruppo di processi.
 *
 * Spazio occupato: al massimo [ROTATE_COUNT] + 1 file da [ROTATE_KB] KB nella memoria privata
 * dell'app, più un piccolo `crash.log` scritto in modo sincrono dal gestore delle eccezioni (il
 * logcat figlio potrebbe non fare in tempo a ricevere le ultime righe prima della chiusura).
 */
object AppLogs {

    private const val TAG = "AppLogs"
    private const val DIR = "logs"
    private const val LOG_FILE = "app.log"
    private const val CRASH_FILE = "crash.log"
    private const val ROTATE_KB = 1024
    private const val ROTATE_COUNT = 4
    private const val CRASH_MAX_BYTES = 256L * 1024
    private const val SNAPSHOT_TIMEOUT_S = 10L
    private const val BUFFERS = "main,system,crash"

    private val lock = Any()
    private var logcat: Process? = null
    private var crashHandlerInstalled = false

    /**
     * Avvia la scrittura dei log su file per il processo corrente. Idempotente: le chiamate
     * successive (activity ricreata, servizio riavviato) non lanciano un secondo logcat.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (logcat != null) return
            val dir = logDir(app)
            installCrashHandler(dir)
            // Riga di confine tra una sessione e l'altra: nei file ruotati restano più avvii.
            Log.i(TAG, "==== Avvio ${app.packageName} ${CreditsDialog.versionName(app)} — pid ${android.os.Process.myPid()}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            logcat = try {
                ProcessBuilder(
                    "logcat",
                    "--pid=${android.os.Process.myPid()}",
                    "-v", "threadtime",
                    "-b", BUFFERS,
                    "-f", File(dir, LOG_FILE).absolutePath,
                    "-r", ROTATE_KB.toString(),
                    "-n", ROTATE_COUNT.toString(),
                )
                    // Con -f logcat non scrive nulla sull'uscita standard; gli eventuali errori li
                    // scarto, così non resta una pipe piena che nessuno legge.
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
            } catch (e: IOException) {
                // Firmware senza logcat accessibile: resta comunque l'istantanea all'esportazione.
                Log.w(TAG, "Scrittura continua dei log non avviata", e)
                null
            }
        }
    }

    /** Nome proposto per il pacchetto, es. `managertv-tv-log-20261116-153000.zip`. */
    fun suggestedFileName(source: String): String =
        "managertv-$source-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.zip"

    /** Scrive un pacchetto ZIP completo su [out] e lo chiude. */
    fun writeZip(context: Context, out: OutputStream, extraInfo: Map<String, String> = emptyMap()) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip -> writeEntries(context, zip, "", extraInfo) }
    }

    /**
     * Aggiunge a [zip] (che resta aperto) i log del processo corrente sotto [prefix]:
     * `info.txt` con dispositivo e stato, i file ruotati dal più vecchio al più recente, il
     * `crash.log` e un'istantanea finale di logcat (le ultime righe, anche se la scrittura
     * continua non fosse partita).
     */
    fun writeEntries(context: Context, zip: ZipOutputStream, prefix: String, extraInfo: Map<String, String> = emptyMap()) {
        val app = context.applicationContext
        // Segno nel log il momento dell'esportazione: aiuta a ritrovare il punto del problema.
        Log.i(TAG, "Esportazione dei log richiesta")
        putText(zip, prefix + "info.txt", describe(app, extraInfo))
        val dir = logDir(app)
        for (file in rotatedFiles(dir)) putFile(zip, prefix + file.name, file)
        File(dir, CRASH_FILE).takeIf { it.isFile }?.let { putFile(zip, prefix + it.name, it) }
        zip.putNextEntry(ZipEntry(prefix + "logcat-snapshot.txt"))
        snapshot(zip)
        zip.closeEntry()
    }

    // ------------------------------------------------------------------ dettagli

    private fun logDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** `app.log.4` … `app.log.1`, `app.log`: ordine cronologico per chi legge il pacchetto. */
    private fun rotatedFiles(dir: File): List<File> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(LOG_FILE) }.orEmpty()
        return files.sortedByDescending { f -> f.name.removePrefix(LOG_FILE).removePrefix(".").toIntOrNull() ?: 0 }
    }

    private fun describe(context: Context, extraInfo: Map<String, String>): String {
        val now = Date()
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(now)
        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
        }
        val lines = linkedMapOf(
            "App" to "${context.packageName} ${CreditsDialog.versionName(context)}",
            "Data" to "$iso (${TimeZone.getDefault().id})",
            "Dispositivo" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}",
            "ABI" to Build.SUPPORTED_ABIS.joinToString(),
            "Processo" to "pid ${android.os.Process.myPid()}, avviato da ${formatUptime(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())}",
            "Memoria" to "libera ${memory.availMem / MB} MB su ${memory.totalMem / MB} MB${if (memory.lowMemory) " (memoria scarsa)" else ""}",
            "Heap app" to "${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / MB} MB usati, massimo ${Runtime.getRuntime().maxMemory() / MB} MB",
            "Spazio app" to "${context.filesDir.usableSpace / MB} MB liberi",
            "Log continuo" to if (synchronized(lock) { logcat?.isAlive == true }) "attivo" else "non attivo (solo istantanea)",
        )
        lines.putAll(extraInfo)
        return lines.entries.joinToString(separator = "\n", postfix = "\n") { (k, v) -> "$k: $v" }
    }

    private fun formatUptime(ms: Long): String {
        val s = ms / 1000
        return "%dh %02dm %02ds".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * Copio solo la lunghezza che il file ha adesso: logcat continua a scriverci e senza limite
     * la voce dello ZIP potrebbe crescere mentre la leggo.
     */
    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        try {
            FileInputStream(file).use { input ->
                zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                copyLimited(input, zip, file.length())
                zip.closeEntry()
            }
        } catch (e: IOException) {
            // File ruotato via tra l'elenco e l'apertura: lo salto, gli altri restano validi.
            Log.w(TAG, "File di log non incluso: ${file.name}", e)
        }
    }

    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long) {
        val buffer = ByteArray(16 * 1024)
        var left = limit
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            left -= n
        }
    }

    /** Istantanea del buffer di logcat del processo corrente, scritta direttamente su [out]. */
    private fun snapshot(out: OutputStream) {
        val process = try {
            ProcessBuilder("logcat", "-d", "--pid=${android.os.Process.myPid()}", "-v", "threadtime", "-b", BUFFERS)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            out.write("Istantanea di logcat non disponibile: ${e.message}\n".toByteArray())
            return
        }
        try {
            process.inputStream.use { it.copyTo(out) }
            if (!process.waitFor(SNAPSHOT_TIMEOUT_S, TimeUnit.SECONDS)) process.destroy()
        } finally {
            process.destroy()
        }
    }

    /**
     * Scrivo l'eccezione fatale in modo sincrono prima di passarla al gestore precedente (che
     * chiude il processo): è la traccia più preziosa e non deve dipendere dal logcat figlio.
     */
    private fun installCrashHandler(dir: File) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val file = File(dir, CRASH_FILE)
                if (file.length() > CRASH_MAX_BYTES) file.renameTo(File(dir, "$CRASH_FILE.1"))
                PrintWriter(FileWriter(file, true)).use { w ->
                    w.println("==== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())} — thread \"${thread.name}\"")
                    error.printStackTrace(w)
                    w.println()
                }
            } catch (_: Throwable) {
                // Nel gestore delle eccezioni fatali non posso permettermi un secondo errore.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private const val MB = 1024L * 1024
}
