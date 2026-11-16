package com.icarocomix.icaromanagertv.common.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Utilità di rete locale comuni alle due app. */
object LocalNetwork {

    /** Nome scelto dall'utente nelle impostazioni (es. "TV salotto"), altrimenti il modello. */
    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL.orEmpty().ifBlank { "Android" }

    /**
     * Indirizzo IPv4 privato del dispositivo, per mostrarlo a schermo. Preferisco la rete attiva
     * (Wi-Fi o Ethernet sulla TV); in mancanza scorro le interfacce scartando loopback e mobili.
     */
    fun localIpv4(context: Context): String? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val fromActive = runCatching {
            cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
        if (fromActive != null) return fromActive
        return lanInterfaces()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }

    /**
     * Destinazioni dei datagrammi broadcast: il broadcast limitato 255.255.255.255 più quello
     * diretto di ogni interfaccia LAN (es. 192.168.1.255). Alcuni router o driver Wi-Fi scartano
     * il primo, altri il secondo: li uso entrambi.
     */
    fun broadcastAddresses(): Set<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        out += InetAddress.getByName("255.255.255.255")
        lanInterfaces().forEach { nif ->
            nif.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
        }
        return out
    }

    /** Vero per gli indirizzi della rete locale (privati, link-local, loopback). */
    fun isLocalAddress(address: InetAddress?): Boolean =
        address != null && (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)

    private fun lanInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") }
    }.getOrDefault(emptyList())
}
