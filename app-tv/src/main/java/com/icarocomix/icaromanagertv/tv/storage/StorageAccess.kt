package com.icarocomix.icaromanagertv.tv.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Permessi di lettura dei dischi, che cambiano con la versione di Android:
 * - fino ad Android 12: READ_EXTERNAL_STORAGE dà accesso a tutto;
 * - da Android 13: READ_MEDIA_* mostra cartelle e file multimediali (gli altri restano nascosti);
 * - da Android 11: "accesso a tutti i file" (MANAGE_EXTERNAL_STORAGE) mostra ogni file.
 */
object StorageAccess {

    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Fino ad Android 9 la scrittura in Download (esportazione dei log) richiede il
            // permesso esplicito; è nello stesso gruppo della lettura, quindi non apre un
            // secondo dialogo se la lettura è già concessa.
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    fun hasRuntime(context: Context): Boolean = runtimePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Vero se l'app vede ogni file dei dischi, non solo quelli multimediali. */
    fun hasFullAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else hasRuntime(context)

    /** Schermate di impostazione per l'accesso completo, dalla più specifica alla generica. */
    fun fullAccessIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
    }

    /** Schermata del permesso di sovrapposizione, necessario per avviare il player da background. */
    fun overlayIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun needsOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)
}
