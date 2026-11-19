package com.icarocomix.icaromanagertv.mobile.launch

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.icarocomix.icaromanagertv.common.atv.AtvRemoteProtocol
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ricerca una tantum, via mDNS, delle TV che annunciano il servizio di controllo remoto di
 * Android TV. A differenza dell'annuncio di Manager TV, questo è attivo appena la TV è
 * accesa, con o senza la nostra app in esecuzione: serve a ritrovare la TV (e il suo eventuale
 * nuovo indirizzo IP dopo un riavvio) quando il server dell'app non risponde.
 */
class AtvServiceFinder(context: Context) {

    /** TV trovata: [name] è il nome del dispositivo scelto dall'utente nelle impostazioni della TV. */
    data class Found(val name: String, val host: String)

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)

    /** Bloccante (fino a [listenMs] più le risoluzioni): va chiamato fuori dal main thread. */
    fun find(listenMs: Long = DEFAULT_LISTEN_MS): List<Found> {
        val manager = nsd ?: return emptyList()
        val lock = acquireMulticastLock()
        val services = LinkedHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(services) { services.putIfAbsent(info.serviceName, info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Ricerca del servizio della TV non avviata (codice $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
        }
        try {
            manager.discoverServices(AtvRemoteProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            Thread.sleep(listenMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "NSD non disponibile", e)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }
        val found = synchronized(services) { services.values.take(MAX_SERVICES) }
        // Le versioni di NSD precedenti ad Android 14 risolvono un servizio alla volta.
        return found.mapNotNull { resolve(manager, it) }
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback esiste solo da Android 14; minSdk 26.
    private fun resolve(manager: NsdManager, info: NsdServiceInfo): Found? {
        val latch = CountDownLatch(1)
        var result: Found? = null
        try {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host
                        val address = if (host is Inet4Address) host.hostAddress else host?.hostAddress?.substringBefore('%')
                        if (address != null) result = Found(resolved.serviceName, address)
                        latch.countDown()
                    }

                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Risoluzione di ${failed.serviceName} non riuscita (codice $errorCode)")
                        latch.countDown()
                    }
                },
            )
            latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Risoluzione NSD non disponibile", e)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return result
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? =
        runCatching {
            appContext.getSystemService(WifiManager::class.java)?.createMulticastLock("icaromanagertv-atv")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

    private companion object {
        const val TAG = "AtvServiceFinder"
        const val DEFAULT_LISTEN_MS = 2_500L
        const val RESOLVE_TIMEOUT_MS = 2_500L
        const val MAX_SERVICES = 8
    }
}
