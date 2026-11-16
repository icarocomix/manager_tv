package com.icarocomix.icaromanagertv.tv.playback

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Volume di sistema della TV comandato dal telefono.
 *
 * Agisco sul flusso [AudioManager.STREAM_MUSIC] e non sul volume interno di ExoPlayer: è lo
 * stesso volume dei tasti del telecomando della TV, resta coerente con l'indicatore di sistema e
 * vale anche per l'audio dei player esterni. Uso sempre [AudioManager.FLAG_SHOW_UI], così chi
 * guarda la TV vede l'indicatore del volume anche quando il comando arriva dal telefono.
 *
 * Volume fisso: sui box collegati via HDMI il sistema spesso blocca il livello locale e inoltra i
 * comandi relativi al televisore o all'amplificatore con HDMI-CEC. In quel caso il livello
 * assoluto non ha senso (non lo conosco e non posso impostarlo): espongo -1 e accetto solo
 * su/giù/muto, che passano comunque da [AudioManager.adjustStreamVolume].
 */
class VolumeControl(context: Context) {

    private val audio: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)

    /** Istantanea del volume da pubblicare: livello (-1 se non regolabile), massimo, muto. */
    data class Snapshot(val level: Int, val max: Int, val muted: Boolean)

    fun snapshot(): Snapshot {
        val am = audio ?: return UNKNOWN
        return try {
            // Lettura economica (una chiamata binder): la ripeto a ogni tick del player, così il
            // telefono vede anche i cambi fatti con il telecomando della TV.
            if (am.isVolumeFixed) {
                Snapshot(level = -1, max = 0, muted = false)
            } else {
                Snapshot(
                    level = am.getStreamVolume(STREAM),
                    max = am.getStreamMaxVolume(STREAM),
                    muted = am.isStreamMute(STREAM),
                )
            }
        } catch (e: RuntimeException) {
            // Alcuni firmware lanciano SecurityException/IllegalState su flussi "protetti".
            Log.w(TAG, "Lettura del volume non riuscita", e)
            UNKNOWN
        }
    }

    fun raise() = adjust(AudioManager.ADJUST_RAISE)

    fun lower() = adjust(AudioManager.ADJUST_LOWER)

    fun toggleMute() = adjust(AudioManager.ADJUST_TOGGLE_MUTE)

    /** Imposta il livello assoluto, limitato al massimo del flusso; ignorato con volume fisso. */
    fun set(level: Int) {
        val am = audio ?: return
        try {
            if (am.isVolumeFixed) return
            val target = level.coerceIn(0, am.getStreamMaxVolume(STREAM))
            // Spostare il cursore su un livello udibile toglie anche il muto, come fa il sistema
            // con i tasti fisici: altrimenti il cursore si muoverebbe senza effetto percepibile.
            if (target > 0 && am.isStreamMute(STREAM)) am.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
            am.setStreamVolume(STREAM, target, AudioManager.FLAG_SHOW_UI)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Impostazione del volume a $level non riuscita", e)
        }
    }

    private fun adjust(direction: Int) {
        val am = audio ?: return
        try {
            am.adjustStreamVolume(STREAM, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Regolazione del volume ($direction) non riuscita", e)
        }
    }

    private companion object {
        const val TAG = "VolumeControl"
        const val STREAM = AudioManager.STREAM_MUSIC
        val UNKNOWN = Snapshot(level = -1, max = 0, muted = false)
    }
}
