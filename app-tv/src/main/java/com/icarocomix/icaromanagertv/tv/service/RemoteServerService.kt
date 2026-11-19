package com.icarocomix.icaromanagertv.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.icarocomix.icaromanagertv.common.net.LocalNetwork
import com.icarocomix.icaromanagertv.common.ui.CreditsDialog
import com.icarocomix.icaromanagertv.tv.R
import com.icarocomix.icaromanagertv.tv.TvApp
import com.icarocomix.icaromanagertv.tv.discovery.TvAnnouncer
import com.icarocomix.icaromanagertv.tv.server.RemoteServer
import com.icarocomix.icaromanagertv.tv.ui.TvMainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Servizio in primo piano che tiene attivi server WebSocket, annuncio in rete (NSD + beacon UDP)
 * e ascolto di montaggio/smontaggio dei dischi USB. Resta attivo anche quando il player esterno
 * copre l'app, così il telefono può continuare a sfogliare e avviare altri file.
 */
class RemoteServerService : Service() {

    private val app get() = application as TvApp
    private var server: RemoteServer? = null
    private var announcer: TvAnnouncer? = null
    private var clientsJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        app.controller.start()
        startServer()
        registerMountReceiver()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Riavvio automatico se il sistema chiude il processo (memoria): il telefono deve trovare
        // la TV senza che nessuno riapra l'app. Da Android 12 il servizio ricreato in background
        // può non ottenere il primo piano (goForeground lo tollera): resta comunque in ascolto
        // finché il processo vive, ed è meglio di un server spento fino alla prossima apertura.
        // Se l'utente l'ha spento (stop()) il sistema non lo ricrea.
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(mountReceiver)
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        clientsJob?.cancel()
        announcer?.stop()
        server?.shutdown()
        app.publishServerInfo(TvApp.ServerInfo())
        super.onDestroy()
    }

    // ------------------------------------------------------------------ server

    private fun startServer() {
        val name = LocalNetwork.deviceName(this)
        val started = try {
            RemoteServer.start(app.controller, app.playback, app.logExporter, app.appScope, name, app.serverId, CreditsDialog.versionName(this))
        } catch (e: IOException) {
            Log.e(TAG, "Server non avviato", e)
            app.publishServerInfo(TvApp.ServerInfo(running = false))
            return
        }
        server = started
        announcer = TvAnnouncer(this, app.serverId, name, started.listeningPort).also { it.start() }
        clientsJob = app.appScope.launch {
            started.clientCount.collect { publishInfo() }
        }
        publishInfo()
    }

    private fun publishInfo() {
        val s = server ?: return
        app.publishServerInfo(
            TvApp.ServerInfo(
                running = true,
                address = LocalNetwork.localIpv4(this),
                port = s.listeningPort,
                clients = s.clientCount.value,
            ),
        )
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Nuova rete (Wi-Fi riconnesso o Ethernet): nuovo IP da mostrare e annuncio da rifare.
                announcer?.onNetworkChanged()
                publishInfo()
            }

            override fun onLost(network: Network) {
                publishInfo()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Callback di rete non registrata", e)
        }
    }

    // ------------------------------------------------------------------ dischi USB

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val path = intent.data?.path
            Log.i(TAG, "Evento archiviazione ${intent.action} su $path")
            when (intent.action) {
                Intent.ACTION_MEDIA_MOUNTED -> app.controller.onVolumesChanged()
                else -> app.controller.onVolumeRemoved(path)
            }
        }
    }

    private fun registerMountReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            // Gli eventi di archiviazione hanno sempre un URI file:// del punto di montaggio.
            addDataScheme("file")
        }
        // Broadcast di sistema: il receiver non va esportato ad altre app.
        ContextCompat.registerReceiver(this, mountReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ------------------------------------------------------------------ notifica

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TvMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) se avviato da background:
            // il servizio resta comunque attivo finché il processo vive.
            Log.w(TAG, "Servizio non portato in primo piano", e)
        }
    }

    companion object {
        private const val TAG = "RemoteServerService"
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1

        private const val PREFS = "server"
        private const val KEY_ENABLED = "enabled"

        /** Avvio voluto dall'utente (apertura dell'app): il server ripartirà anche al boot. */
        fun start(context: Context) {
            setEnabled(context, true)
            ContextCompat.startForegroundService(context, Intent(context, RemoteServerService::class.java))
        }

        /** Avvio all'accensione della TV (vedi [BootReceiver]): non tocca la preferenza. */
        fun startFromBoot(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteServerService::class.java))
        }

        /** Spegnimento voluto dall'utente: niente riavvio al boot finché non riapre l'app. */
        fun stop(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, RemoteServerService::class.java))
        }

        /** true se l'ultimo comando dell'utente è stato di tenere acceso il server. */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        private fun setEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Scrivo solo se cambia: start() è chiamato a ogni apertura dell'activity.
            if (prefs.getBoolean(KEY_ENABLED, false) != enabled) prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }
}
