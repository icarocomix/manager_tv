package com.icarocomix.icaromanagertv.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.icarocomix.icaromanagertv.common.net.LocalNetwork
import com.icarocomix.icaromanagertv.common.protocol.Beacon
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Rende la TV trovabile dal telefono senza configurazione, su due canali indipendenti:
 *
 * 1. **NSD/mDNS** (servizio [Protocol.SERVICE_TYPE]): standard, risolto dal sistema.
 * 2. **Beacon UDP** in broadcast su [Protocol.BEACON_PORT] ogni 2 secondi, con risposta
 *    immediata alle sonde del telefono: funziona anche dove il router filtra il multicast.
 *
 * Il telefono usa il primo canale che risponde.
 */
class TvAnnouncer(
    context: Context,
    private val serverId: String,
    private val serverName: String,
    private val port: Int,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null

    @Volatile private var running = false
    private var beaconThread: Thread? = null
    @Volatile private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (running) return
        running = true
        registerNsd()
        acquireMulticastLock()
        beaconThread = Thread(::beaconLoop, "udp-beacon").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        registration?.let { l -> runCatching { nsd?.unregisterService(l) } }
        registration = null
        // Chiudere il socket sblocca subito la receive() del thread del beacon.
        socket?.close()
        beaconThread?.interrupt()
        beaconThread = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    /**
     * Rete cambiata (Wi-Fi riconnesso, nuovo IP): ripeto la registrazione NSD, che su diverse
     * versioni di Android non segue da sola il cambio di interfaccia. Il beacon UDP si adatta da
     * sé perché ricalcola gli indirizzi di broadcast a ogni invio.
     */
    fun onNetworkChanged() {
        if (!running) return
        registration?.let { l -> runCatching { nsd?.unregisterService(l) } }
        registration = null
        registerNsd()
    }

    // ------------------------------------------------------------------ NSD

    private fun registerNsd() {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            // Il nome del servizio deve stare entro 63 byte UTF-8: tronco il nome della TV.
            serviceName = (Protocol.SERVICE_NAME_PREFIX + serverName).take(60)
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
            setAttribute(Protocol.TXT_ID, serverId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Servizio NSD annunciato: ${info.serviceName} porta $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Annuncio NSD non riuscito (codice $errorCode): resta il beacon UDP")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            registration = null
            Log.w(TAG, "NSD non disponibile", e)
        }
    }

    // ------------------------------------------------------------------ UDP

    private fun acquireMulticastLock() {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = runCatching {
            wifi.createMulticastLock("icaromanagertv-beacon").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun beaconLoop() {
        val announce = Beacon.Announce(serverId, serverName, port).encode()
        val buffer = ByteArray(Beacon.MAX_SIZE + 1)
        while (running) {
            try {
                // Socket legato alla porta del beacon con SO_REUSEADDR: riceve le sonde dei
                // telefoni e invia gli annunci. Lo ricreo se cade (es. cambio di rete).
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    s.soTimeout = Protocol.BEACON_INTERVAL_MS.toInt()
                    s.bind(InetSocketAddress(Protocol.BEACON_PORT))
                    socket = s
                    var nextAnnounce = 0L
                    while (running) {
                        val now = System.currentTimeMillis()
                        if (now >= nextAnnounce) {
                            sendBroadcast(s, announce)
                            nextAnnounce = now + Protocol.BEACON_INTERVAL_MS
                        }
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            s.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        if (Beacon.decode(packet.data, packet.length) == Beacon.Probe) {
                            // Risposta diretta al telefono che ha chiesto: niente attesa del beacon.
                            runCatching { s.send(DatagramPacket(announce, announce.size, packet.socketAddress)) }
                        }
                    }
                }
            } catch (e: SocketException) {
                if (!running) break
                Log.w(TAG, "Socket del beacon non disponibile, riprovo: ${e.message}")
                sleepQuietly(RETRY_DELAY_MS)
            } catch (e: Exception) {
                if (!running) break
                Log.e(TAG, "Errore nel beacon UDP", e)
                sleepQuietly(RETRY_DELAY_MS)
            } finally {
                socket = null
            }
        }
    }

    private fun sendBroadcast(s: DatagramSocket, payload: ByteArray) {
        LocalNetwork.broadcastAddresses().forEach { address ->
            try {
                s.send(DatagramPacket(payload, payload.size, address, Protocol.BEACON_PORT))
            } catch (e: Exception) {
                // Un indirizzo irraggiungibile (interfaccia appena scesa) non blocca gli altri.
                Log.d(TAG, "Beacon verso $address non inviato: ${e.message}")
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private companion object {
        const val TAG = "TvAnnouncer"
        const val RETRY_DELAY_MS = 3_000L
    }
}
