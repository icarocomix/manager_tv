package com.icarocomix.icaromanagertv.common.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.icarocomix.icaromanagertv.common.R

/**
 * Dialog "Info / Crediti": logo, nome e versione installata dell'app, autore (IcaroComix) con
 * il logo aziendale, descrizione e librerie usate. Condiviso dalle due app; ciascuna passa il
 * proprio testo descrittivo.
 */
object CreditsDialog {

    fun show(context: Context, @StringRes description: Int, @StringRes libraries: Int) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_credits, null)
        view.findViewById<TextView>(R.id.credits_app_name).text = appLabel(context)
        view.findViewById<TextView>(R.id.credits_version).text =
            context.getString(R.string.credits_version, versionName(context))
        view.findViewById<TextView>(R.id.credits_description).setText(description)
        view.findViewById<TextView>(R.id.credits_libraries).setText(libraries)
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton(R.string.credits_close, null)
            .show()
        // Sulla TV il focus deve partire dal pulsante, altrimenti il telecomando non chiude nulla.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun appLabel(context: Context): CharSequence =
        context.applicationInfo.loadLabel(context.packageManager)

    /**
     * Versione letta dal PackageManager: con AGP 9 BuildConfig non è generato di default e non
     * vale la pena attivarlo solo per questo. È la versione di version.properties.
     */
    fun versionName(context: Context): String =
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "?"
        } catch (_: PackageManager.NameNotFoundException) {
            "?"
        }
}
