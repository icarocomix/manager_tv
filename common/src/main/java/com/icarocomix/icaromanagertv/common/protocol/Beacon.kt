package com.icarocomix.icaromanagertv.common.protocol

import org.json.JSONException
import org.json.JSONObject

/**
 * Datagrammi UDP di scoperta, alternativi a NSD per le reti che filtrano il multicast mDNS.
 *
 * - [Announce]: inviato dalla TV in broadcast ogni [Protocol.BEACON_INTERVAL_MS] e in risposta
 *   diretta a ogni sonda; porta nome, identificativo e porta TCP del server.
 * - [Probe]: inviato dal telefono in broadcast per avere subito una risposta senza attendere il
 *   beacon periodico.
 */
sealed class Beacon {
    data class Announce(val id: String, val name: String, val port: Int, val protocol: Int = Protocol.VERSION) : Beacon()
    data object Probe : Beacon()

    fun encode(): ByteArray {
        val o = JSONObject().put("magic", Protocol.MAGIC)
        when (this) {
            is Announce -> o.put("t", "announce").put("id", id).put("name", name).put("port", port).put("protocol", protocol)
            Probe -> o.put("t", "probe")
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** Dimensione massima accettata: i beacon veri stanno ben sotto, il resto è rumore. */
        const val MAX_SIZE = 1024

        fun decode(data: ByteArray, length: Int): Beacon? {
            if (length <= 0 || length > MAX_SIZE) return null
            return try {
                val o = JSONObject(String(data, 0, length, Charsets.UTF_8))
                if (o.optString("magic") != Protocol.MAGIC) return null
                when (o.optString("t")) {
                    "announce" -> {
                        val port = o.optInt("port", -1)
                        val id = o.optString("id")
                        if (port !in 1..65535 || id.isEmpty()) null
                        else Announce(id, o.optString("name").take(80), port, o.optInt("protocol"))
                    }
                    "probe" -> Probe
                    else -> null
                }
            } catch (_: JSONException) {
                null
            }
        }
    }
}
