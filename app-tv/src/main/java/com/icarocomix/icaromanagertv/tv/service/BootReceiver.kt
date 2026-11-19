package com.icarocomix.icaromanagertv.tv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Rimette in ascolto il server senza che nessuno apra l'app, se l'utente non l'aveva spento:
 * - all'accensione della TV (anche dopo aver staccato la spina): BOOT_COMPLETED e le varianti
 *   di "accensione rapida" usate da alcuni produttori al posto del boot completo;
 * - dopo un aggiornamento dell'app (MY_PACKAGE_REPLACED): l'installazione uccide il processo e,
 *   senza questo, il server resterebbe spento fino alla successiva apertura.
 * Così il telefono trova sempre la TV e può portarne in primo piano l'interfaccia.
 *
 * L'avvio di un servizio in primo piano da questi broadcast è tra le eccezioni ammesse da
 * Android 12 in poi; il tipo `specialUse` non rientra tra quelli vietati al boot da Android 15.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in TRIGGERS) return
        if (!RemoteServerService.isEnabled(context)) {
            Log.i(TAG, "Server spento dall'utente: nessun avvio ($action)")
            return
        }
        try {
            RemoteServerService.startFromBoot(context)
            Log.i(TAG, "Server avviato ($action)")
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (firmware più restrittivi) o simili: il
            // server partirà alla prima apertura dell'app.
            Log.w(TAG, "Avvio del server non consentito ($action): ${e.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val TRIGGERS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
