package com.icarocomix.icaromanagertv.mobile.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.icarocomix.icaromanagertv.common.net.LocalNetwork
import com.icarocomix.icaromanagertv.common.protocol.Beacon
import com.icarocomix.icaromanagertv.common.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.ArrayDeque

/** TV trovata in rete. [id] è stabile (generato dalla TV), host e porta possono cambiare. */
data class TvEndpoint(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val viaNsd: Boolean = false,
    val lastBeacon: Long = 0,
)

/**
 * Ricerca delle TV in rete locale, in background e senza interazione, su due canali paralleli:
 * NSD/mDNS e beacon UDP (con sonda attiva in broadcast per una risposta immediata). Il primo che
 * risponde popola [endpoints]; il client si collega da solo.
 */
class TvDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)

    private val _endpoints = MutableStateFlow<Map<String, TvEndpoint>>(emptyMap())
    val endpoints: StateFlow<Map<String, TvEndpoint>> = _endpoints.asStateFlow()

    @Volatile private var running = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var udpThread: Thread? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Le versioni di NSD precedenti ad Android 14 accettano una sola risoluzione alla volta:
    // accodo i servizi trovati e li risolvo in sequenza.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val nsdNames = HashMap<String, String>() // nome servizio → id endpoint

    @Synchronized
    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        startNsd()
        udpThread = Thread(::udpLoop, "udp-discovery").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        stopNsd()
        udpSocket?.close()
        udpThread?.interrupt()
        udpThread = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    /** Cambio di rete: NSD va riavviato (non segue il cambio di interfaccia), il resto si adatta. */
    @Synchronized
    fun restart() {
        if (!running) return
        stopNsd()
        startNsd()
        probeNow()
    }

    /** Invia subito una sonda UDP in broadcast: la TV risponde senza attendere il beacon. */
    fun probeNow() {
        val socket = udpSocket ?: return
        Thread({ sendProbe(socket) }, "udp-probe").start()
    }

    /** Toglie le TV che non danno segni di vita da un po' (beacon scaduto e non annunciate via NSD). */
    fun pruneStale() {
        val now = SystemClock.elapsedRealtime()
        _endpoints.update { map -> map.filterValues { it.viaNsd || now - it.lastBeacon < STALE_MS } }
    }

    private fun upsert(endpoint: TvEndpoint) {
        _endpoints.update { map ->
            val old = map[endpoint.id]
            val merged = if (old == null) {
                endpoint
            } else {
                endpoint.copy(
                    viaNsd = endpoint.viaNsd || old.viaNsd,
                    lastBeacon = maxOf(endpoint.lastBeacon, old.lastBeacon),
                )
            }
            if (merged == old) map else map + (endpoint.id to merged)
        }
    }

    // ------------------------------------------------------------------ NSD

    private fun startNsd() {
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (Protocol.tvNameFromService(info.serviceName) == null) return
                enqueueResolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val id = synchronized(nsdNames) { nsdNames.remove(info.serviceName) } ?: return
                _endpoints.update { map ->
                    val e = map[id] ?: return@update map
                    if (SystemClock.elapsedRealtime() - e.lastBeacon < STALE_MS) map + (id to e.copy(viaNsd = false)) else map - id
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Ricerca NSD non avviata (codice $errorCode): resta il canale UDP")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        try {
            manager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            discoveryListener = null
            Log.w(TAG, "NSD non disponibile", e)
        }
    }

    private fun stopNsd() {
        discoveryListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        discoveryListener = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback esiste solo da Android 14; minSdk 26.
    private fun resolveNext() {
        val info = synchronized(resolveQueue) {
            resolveQueue.pollFirst().also { if (it == null) resolving = false }
        } ?: return
        val manager = nsd ?: return
        try {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host
                        if (host != null && running) {
                            val id = resolved.attributes[Protocol.TXT_ID]?.toString(Charsets.UTF_8)
                                ?.takeIf { it.isNotBlank() } ?: "nsd:${resolved.serviceName}"
                            synchronized(nsdNames) { nsdNames[resolved.serviceName] = id }
                            // Preferisco l'IPv4: alcuni router non instradano bene gli IPv6 link-local.
                            val address = if (host is Inet4Address) host.hostAddress else host.hostAddress?.substringBefore('%')
                            if (address != null) {
                                upsert(
                                    TvEndpoint(
                                        id = id,
                                        name = Protocol.tvNameFromService(resolved.serviceName) ?: resolved.serviceName,
                                        host = address,
                                        port = resolved.port,
                                        viaNsd = true,
                                    ),
                                )
                            }
                        }
                        resolveNext()
                    }

                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Risoluzione di ${failed.serviceName} non riuscita (codice $errorCode)")
                        resolveNext()
                    }
                },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Risoluzione NSD non disponibile", e)
            resolveNext()
        }
    }

    // ------------------------------------------------------------------ UDP

    private fun acquireMulticastLock() {
        // Senza lock molti telefoni scartano i pacchetti broadcast/multicast a schermo acceso
        // per risparmiare batteria: lo tengo solo mentre la ricerca è attiva.
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = runCatching {
            wifi.createMulticastLock("icaromanagertv-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun udpLoop() {
        val buffer = ByteArray(Beacon.MAX_SIZE + 1)
        while (running) {
            try {
                DatagramSocket(null).use { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    s.soTimeout = 1_000
                    s.bind(InetSocketAddress(Protocol.BEACON_PORT))
                    udpSocket = s
                    var nextProbe = 0L
                    while (running) {
                        val now = SystemClock.elapsedRealtime()
                        if (now >= nextProbe) {
                            sendProbe(s)
                            nextProbe = now + PROBE_INTERVAL_MS
                            pruneStale()
                        }
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            s.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val beacon = Beacon.decode(packet.data, packet.length) as? Beacon.Announce ?: continue
                        val host = packet.address?.hostAddress ?: continue
                        upsert(
                            TvEndpoint(
                                id = beacon.id,
                                name = beacon.name,
                                host = host,
                                port = beacon.port,
                                lastBeacon = SystemClock.elapsedRealtime(),
                            ),
                        )
                    }
                }
            } catch (e: SocketException) {
                if (!running) break
                Log.w(TAG, "Socket UDP non disponibile, riprovo: ${e.message}")
                sleepQuietly(2_000)
            } catch (e: Exception) {
                if (!running) break
                Log.e(TAG, "Errore nella ricerca UDP", e)
                sleepQuietly(2_000)
            } finally {
                udpSocket = null
            }
        }
    }

    private fun sendProbe(socket: DatagramSocket) {
        val payload = Beacon.Probe.encode()
        LocalNetwork.broadcastAddresses().forEach { address ->
            try {
                socket.send(DatagramPacket(payload, payload.size, address, Protocol.BEACON_PORT))
            } catch (e: Exception) {
                Log.d(TAG, "Sonda verso $address non inviata: ${e.message}")
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
        const val TAG = "TvDiscovery"
        const val PROBE_INTERVAL_MS = 4_000L
        const val STALE_MS = 15_000L
    }
}
