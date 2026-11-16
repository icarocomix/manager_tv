package com.icarocomix.icaromanagertv.tv.controller

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.icarocomix.icaromanagertv.tv.R
import com.icarocomix.icaromanagertv.tv.TvApp
import com.icarocomix.icaromanagertv.tv.storage.MediaTypes
import com.icarocomix.icaromanagertv.tv.storage.Node
import com.icarocomix.icaromanagertv.tv.storage.StorageRepository

/**
 * Apre un file con il player installato (VLC, MX Player, Kodi, player di sistema…) tramite
 * `ACTION_VIEW`. Il file viaggia come URI content:// con permesso di lettura temporaneo.
 */
class MediaLauncher(private val context: Context, private val storage: StorageRepository) {

    /** Esito dell'apertura: [started] se l'Intent è stato consegnato, [message] da mostrare all'utente. */
    data class Result(val started: Boolean, val message: String? = null)

    fun open(node: Node): Result {
        val uri = try {
            storage.openUri(node)
        } catch (e: IllegalArgumentException) {
            // FileProvider rifiuta i percorsi fuori da quelli dichiarati in file_paths.xml.
            Log.w(TAG, "URI non generabile per ${node.name}", e)
            return Result(false, context.getString(R.string.error_open_uri, node.name))
        }
        val specific = storage.mimeOf(node)
        // Provo prima il MIME specifico (es. video/x-matroska) e poi quello generico della
        // famiglia: alcuni player si registrano solo per video/* e ignorano i tipi esatti.
        val types = listOfNotNull(specific, MediaTypes.genericFor(node.kind)).distinct()
        for (type in types) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                // ClipData propaga il permesso di lettura anche ai player che rilanciano
                // internamente un'altra activity con lo stesso URI.
                clipData = ClipData.newRawUri(node.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                // Titolo mostrato da VLC e MX Player al posto dell'URI content://.
                putExtra("title", node.name)
            }
            try {
                context.startActivity(intent)
                return Result(true, backgroundWarning())
            } catch (_: ActivityNotFoundException) {
                Log.i(TAG, "Nessun player per $type, provo il tipo successivo")
            } catch (e: SecurityException) {
                Log.w(TAG, "Avvio del player negato per ${node.name}", e)
                return Result(false, context.getString(R.string.error_open_denied, node.name))
            }
        }
        return Result(false, context.getString(R.string.error_no_player, node.name))
    }

    /**
     * Da Android 10 un'app in background non può avviare activity (il sistema scarta l'avvio
     * senza eccezioni) a meno che abbia il permesso di sovrapposizione. Se siamo in quel caso
     * lo segnalo, così l'utente sa perché il player non è comparso.
     */
    fun backgroundWarning(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (TvApp.isInForeground) return null
        if (Settings.canDrawOverlays(context)) return null
        return context.getString(R.string.warning_background_start)
    }

    private companion object {
        const val TAG = "MediaLauncher"
    }
}
