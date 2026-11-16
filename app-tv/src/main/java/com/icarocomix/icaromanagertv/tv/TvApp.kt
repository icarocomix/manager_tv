package com.icarocomix.icaromanagertv.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.icarocomix.icaromanagertv.common.log.AppLogs
import com.icarocomix.icaromanagertv.tv.controller.BrowserController
import com.icarocomix.icaromanagertv.tv.controller.MediaLauncher
import com.icarocomix.icaromanagertv.tv.diagnostics.LogExporter
import com.icarocomix.icaromanagertv.tv.playback.PlaybackController
import com.icarocomix.icaromanagertv.tv.playback.ProgressStore
import com.icarocomix.icaromanagertv.tv.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Application dell'app TV: possiede il controller del file manager, condiviso tra l'activity
 * (UI) e il servizio (server di rete). Così lo stato sopravvive alla chiusura dell'activity
 * mentre il player esterno è in primo piano, e TV e telefono vedono sempre lo stesso stato.
 */
class TvApp : Application() {

    /** Scope di processo: vive quanto l'app, figli indipendenti (un errore non cancella gli altri). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var storage: StorageRepository
        private set
    lateinit var controller: BrowserController
        private set
    lateinit var playback: PlaybackController
        private set

    /** Esportazione dei log (pulsante della TV ed endpoint HTTP per il telefono). */
    val logExporter: LogExporter by lazy { LogExporter(this) }

    /** Identificativo stabile della TV: il telefono lo usa per riconoscerla anche se cambia IP. */
    val serverId: String by lazy {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(KEY_SERVER_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_SERVER_ID, it).apply()
        }
    }

    /** Stato del server pubblicato dal servizio e mostrato dalla UI. */
    data class ServerInfo(val running: Boolean = false, val address: String? = null, val port: Int = 0, val clients: Int = 0)

    private val _serverInfo = MutableStateFlow(ServerInfo())
    val serverInfo: StateFlow<ServerInfo> = _serverInfo.asStateFlow()

    fun publishServerInfo(info: ServerInfo) {
        _serverInfo.value = info
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Per primo: così finiscono nei file anche i log dell'inizializzazione qui sotto.
        AppLogs.start(this)
        storage = StorageRepository(this)
        val launcher = MediaLauncher(this, storage)
        // Un solo archivio delle posizioni: il player lo scrive, il file manager lo legge per le
        // barre di avanzamento nelle righe.
        val progressStore = ProgressStore(this)
        // Il player notifica titolo e messaggi tramite il controller del file manager: così
        // finiscono nello stato e nei toast già trasmessi a TV e telefono. Le lambda leggono
        // `controller` solo al momento della chiamata, quando è già inizializzato.
        playback = PlaybackController(
            context = this,
            store = progressStore,
            launcher = launcher,
            scope = appScope,
            onNowPlaying = { name -> controller.setNowPlaying(name) },
            onToast = { text -> controller.announce(text) },
        )
        controller = BrowserController(this, storage, launcher, playback, progressStore, appScope)
        registerActivityLifecycleCallbacks(ForegroundTracker)
    }

    /**
     * Conta le activity avviate per sapere se l'app è in primo piano: da Android 10 l'avvio di
     * un player da background è bloccato e va segnalato all'utente.
     */
    private object ForegroundTracker : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            started++
            isInForeground = started > 0
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            isInForeground = started > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        private const val PREFS = "tv"
        private const val KEY_SERVER_ID = "server_id"

        lateinit var instance: TvApp
            private set

        @Volatile
        var isInForeground: Boolean = false
            private set
    }
}
