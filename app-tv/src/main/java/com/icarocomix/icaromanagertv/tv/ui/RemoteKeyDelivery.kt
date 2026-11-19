package com.icarocomix.icaromanagertv.tv.ui

import android.app.Dialog
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Consegna i tasti del pad del telefono alla finestra dell'app che ha il focus (activity, dialog,
 * pannelli impostazioni/sottotitoli di Media3), con lo stesso effetto del telecomando fisico.
 *
 * Prima li iniettavo come eventi di sistema con [Instrumentation]: un'app senza il permesso
 * INJECT_EVENTS può farlo solo verso le proprie finestre, e se in quell'istante il sistema
 * considera "a fuoco" un'altra finestra (o il produttore restringe l'iniezione) l'evento viene
 * scartato senza eccezioni: il pad del telefono risultava muto. Ora consegno l'evento io,
 * dentro il processo, alla radice della finestra con il focus.
 *
 * Consegnando l'evento direttamente alle viste manca però il lavoro che normalmente fa
 * `ViewRootImpl`: lo spostamento del focus con le frecce e il "Indietro" (che, con il ritorno
 * predittivo attivo, non passa più da `onKeyUp`). Li replico qui: le frecce non consumate da
 * nessuna vista spostano il focus con [View.focusSearch], come fa il sistema; "Indietro" va al
 * dispatcher di activity o dialog quando la finestra è loro.
 *
 * Su Android 8–9 manca l'elenco pubblico delle finestre del processo ([WindowInspector]): lì
 * cerco tra activity e dialog noti e, se nessuno ha il focus (es. un pannello di Media3), torno
 * all'iniezione di sistema.
 */
class RemoteKeyDelivery(
    private val activity: ComponentActivity,
    /** Dialog dell'app aperto in questo momento, se l'activity lo conosce. */
    private val currentDialog: () -> Dialog?,
) {

    private val instrumentation = Instrumentation()
    private val injector: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-key-inject").apply { isDaemon = true }
    }

    /** Da chiamare sul main thread. */
    fun deliver(keyCode: Int) {
        val root = focusedWindowRoot()
        if (root == null) {
            Log.w(TAG, "Nessuna finestra dell'app ha il focus: provo l'iniezione di sistema del tasto $keyCode")
            injectFallback(keyCode)
            return
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && deliverBack(root)) return

        // Fuori dal touch mode, come farebbe un tasto vero: in touch mode le viste non
        // "focusable in touch mode" (pulsanti) rifiuterebbero il focus.
        if (root.isInTouchMode) root.findFocus()?.requestFocusFromTouch()

        val now = SystemClock.uptimeMillis()
        val down = keyEvent(now, KeyEvent.ACTION_DOWN, keyCode)
        val handled = root.dispatchKeyEvent(down)
        val direction = FOCUS_DIRECTIONS[keyCode]
        if (!handled && direction != null) moveFocus(root, direction)
        root.dispatchKeyEvent(keyEvent(now, KeyEvent.ACTION_UP, keyCode))
    }

    fun shutdown() {
        injector.shutdownNow()
    }

    /**
     * Radice della finestra del processo che ha il focus. Più finestre possono essere aperte
     * (activity sotto, dialog o popup sopra): il focus ce l'ha una sola, quella in cima.
     */
    private fun focusedWindowRoot(): View? {
        val candidates: List<View> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WindowInspector.getGlobalWindowViews()
        } else {
            listOfNotNull(activity.window.decorView, currentDialog()?.window?.decorView)
        }
        return candidates.lastOrNull { it.isAttachedToWindow && it.hasWindowFocus() }
    }

    /**
     * "Indietro" per le finestre che usano il dispatcher di AndroidX (activity e dialog
     * dell'app): chiamandolo direttamente ottengo lo stesso effetto del tasto vero anche con il
     * ritorno predittivo, che sui sistemi recenti non passa da onKeyUp. Le altre finestre
     * (popup di Media3) gestiscono "Indietro" nel proprio dispatchKeyEvent.
     */
    private fun deliverBack(root: View): Boolean {
        if (root === activity.window.decorView) {
            activity.onBackPressedDispatcher.onBackPressed()
            return true
        }
        val dialog = currentDialog() as? ComponentDialog ?: return false
        if (dialog.window?.decorView !== root) return false
        dialog.onBackPressedDispatcher.onBackPressed()
        return true
    }

    /**
     * Spostamento del focus come in `ViewRootImpl`: cerco la vista successiva nella direzione
     * del tasto partendo da quella con il focus, passando il rettangolo di partenza perché le
     * liste e i gruppi possano scegliere l'elemento allineato; senza focus ripristino quello
     * predefinito della finestra.
     */
    private fun moveFocus(root: View, direction: Int) {
        val focused = root.findFocus()
        if (focused == null) {
            root.restoreDefaultFocus()
            return
        }
        val next = focused.focusSearch(direction)
        if (next != null && next !== focused) {
            val rect = Rect()
            focused.getFocusedRect(rect)
            if (root is ViewGroup) {
                root.offsetDescendantRectToMyCoords(focused, rect)
                root.offsetRectIntoDescendantCoords(next, rect)
            }
            if (next.requestFocus(direction, rect)) {
                root.playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction))
                return
            }
        }
        root.dispatchUnhandledMove(focused, direction)
    }

    private fun keyEvent(downTime: Long, action: Int, keyCode: Int): KeyEvent =
        KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_DPAD,
        )

    /**
     * Ultima risorsa (Android 8–9 con un popup aperto): iniezione di sistema, sincrona e quindi
     * fuori dal main thread, su un thread seriale per non invertire l'ordine dei tasti.
     */
    private fun injectFallback(keyCode: Int) {
        if (injector.isShutdown) return
        injector.execute {
            try {
                instrumentation.sendKeyDownUpSync(keyCode)
            } catch (e: Exception) {
                Log.w(TAG, "Iniezione del tasto $keyCode non riuscita: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "RemoteKeyDelivery"

        val FOCUS_DIRECTIONS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT,
        )
    }
}
