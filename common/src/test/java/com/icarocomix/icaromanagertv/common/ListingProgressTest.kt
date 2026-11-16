package com.icarocomix.icaromanagertv.common

import com.icarocomix.icaromanagertv.common.protocol.ListingProgress
import com.icarocomix.icaromanagertv.common.protocol.ProtocolCodec
import com.icarocomix.icaromanagertv.common.protocol.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ListingProgressTest {

    @Test
    fun roundTrip() {
        val progress = ListingProgress(seq = 42, permille = mapOf(0 to 1, 7 to 500, 1200 to 999))
        val decoded = ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.ProgressMsg(progress)))
        assertEquals(ServerMessage.ProgressMsg(progress), decoded)
    }

    @Test
    fun emptyAndMalformedPairs() {
        val empty = ListingProgress(seq = 3, permille = emptyMap())
        assertEquals(ServerMessage.ProgressMsg(empty), ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.ProgressMsg(empty))))
        // Indici negativi, valori fuori intervallo e coppie spaiate vengono scartati.
        val decoded = ProtocolCodec.decodeServer("{\"t\":\"progress\",\"seq\":5,\"p\":[-1,10,2,0,3,1000,4,250,9]}")
        assertEquals(ServerMessage.ProgressMsg(ListingProgress(5, mapOf(4 to 250))), decoded)
    }

    @Test
    fun permilleOf() {
        assertEquals(-1, ListingProgress.permilleOf(10_000, -1))
        assertEquals(-1, ListingProgress.permilleOf(10_000, 0))
        assertEquals(1, ListingProgress.permilleOf(0, 60_000))
        assertEquals(500, ListingProgress.permilleOf(30_000, 60_000))
        assertEquals(999, ListingProgress.permilleOf(60_000, 60_000))
        assertEquals(-1, ListingProgress(1, mapOf(2 to 300)).of(3))
        assertEquals(300, ListingProgress(1, mapOf(2 to 300)).of(2))
    }
}
