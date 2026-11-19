package com.icarocomix.icaromanagertv.tv.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.icarocomix.icaromanagertv.tv.R
import com.icarocomix.icaromanagertv.tv.TvApp

/**
 * Porta in primo piano l'interfaccia della TV su richiesta del telefono, anche quando l'app è
 * in background (solo servizio attivo, altra app o launcher in primo piano).
 *
 * Da Android 10 un'app in background non può avviare activity: il sistema scarta l'avvio senza
 * eccezioni. L'eccezione prevista per questo caso d'uso è il permesso «Mostra sopra altre app»
 * (SYSTEM_ALERT_WINDOW), già richiesto dalla TV per l'avvio del player; da Android 15 vale però
 * solo se l'app ha davvero una finestra sovrapposta visibile. Per questo, prima dell'avvio,
 * aggiungo una finestra overlay di 1×1 pixel trasparente e non interattiva, e la tolgo poco dopo.
 *
 * Il risultato non è osservabile in modo sincrono: verifico dopo qualche istante se l'app è
 * davvero in primo piano e, se non lo è, lo segnalo tramite [onFailure] (toast su TV e telefono).
 */
class UiLauncher(private val context: Context, private val onFailure: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    /** Chiamabile da qualunque thread: il lavoro avviene sul main thread (finestre e activity). */
    fun bringToFront() {
        main.post { launch() }
    }

    private fun launch() {
        if (TvApp.isInForeground) {
            // Già in primo piano: nessuna restrizione, riporto in cima l'activity principale
            // (innocuo se lo è già).
            startMainActivity()
            return
        }
        val needsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val canOverlay = !needsOverlay || Settings.canDrawOverlays(context)
        val overlay = if (needsOverlay && canOverlay) addOverlayWindow() else null
        if (overlay == null) {
            startMainActivity()
        } else {
            // Attendo che la finestra overlay sia disposta e visibile per il sistema: il
            // controllo sull'avvio da background guarda le finestre già mostrate.
            overlay.postDelayed({
                startMainActivity()
                main.postDelayed({ removeOverlayWindow(overlay) }, OVERLAY_LINGER_MS)
            }, OVERLAY_SETTLE_MS)
        }
        main.postDelayed({
            if (!TvApp.isInForeground) {
                Log.w(TAG, "L'interfaccia non è arrivata in primo piano (sovrapposizione concessa: $canOverlay)")
                onFailure(
                    context.getString(if (canOverlay) R.string.ui_front_blocked else R.string.ui_front_needs_overlay),
                )
            }
        }, VERIFY_AFTER_MS)
    }

    private fun startMainActivity() {
        // singleTask nel manifest: se l'activity esiste già il sistema porta in primo piano il
        // suo task e la riceve in onNewIntent, senza crearne una seconda.
        val intent = Intent(context, TvMainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            // SecurityException/ActivityNotFoundException su firmware particolari: lo registro,
            // la verifica successiva informa l'utente.
            Log.e(TAG, "Avvio dell'interfaccia non riuscito", e)
        }
    }

    /** Finestra overlay invisibile (1×1, trasparente, non tocca né prende il focus). */
    private fun addOverlayWindow(): View? {
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return null
        val view = View(context)
        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        return try {
            windowManager.addView(view, params)
            view
        } catch (e: RuntimeException) {
            // Permesso revocato nel frattempo o tipo di finestra negato dal firmware: provo
            // comunque l'avvio diretto.
            Log.w(TAG, "Finestra overlay non aggiunta: ${e.message}")
            null
        }
    }

    private fun removeOverlayWindow(view: View) {
        try {
            context.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Finestra overlay già rimossa: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "UiLauncher"
        const val OVERLAY_SETTLE_MS = 150L
        const val OVERLAY_LINGER_MS = 1_500L
        const val VERIFY_AFTER_MS = 3_000L
    }
}
