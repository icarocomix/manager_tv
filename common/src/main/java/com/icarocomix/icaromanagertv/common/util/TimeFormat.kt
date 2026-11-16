package com.icarocomix.icaromanagertv.common.util

import java.util.Locale

/** Formattazione dei tempi di riproduzione, identica su TV e telefono. */
object TimeFormat {

    /**
     * "m:ss" sotto l'ora, "h:mm:ss" oltre; valori negativi o ignoti → "--:--".
     * Tronco ai secondi interi: è ciò che l'utente si aspetta di leggere su un contatore.
     */
    fun format(ms: Long): String {
        if (ms < 0) return "--:--"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
