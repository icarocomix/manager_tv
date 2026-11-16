package com.icarocomix.icaromanagertv.common

import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.Entry
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import com.icarocomix.icaromanagertv.common.protocol.Listing
import com.icarocomix.icaromanagertv.common.protocol.ProtocolCodec
import com.icarocomix.icaromanagertv.common.protocol.RemoteKey
import com.icarocomix.icaromanagertv.common.protocol.RemoteState
import com.icarocomix.icaromanagertv.common.protocol.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolCodecTest {

    @Test
    fun listingRoundTrip() {
        val listing = Listing(
            seq = 7,
            crumbs = listOf("USB Toshiba", "Film"),
            entries = listOf(
                Entry("Azione", EntryKind.DIRECTORY, -1, 1_700_000_000_000),
                Entry("The.Matrix.1999.mkv", EntryKind.VIDEO, 4_500_000_000, 1_600_000_000_000, "Film/Fantascienza"),
                Entry("Città.jpg", EntryKind.IMAGE, 12_345, 0),
            ),
            searchResults = true,
            complete = false,
        )
        val decoded = ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.ListingMsg(listing)))
        assertEquals(ServerMessage.ListingMsg(listing), decoded)
    }

    @Test
    fun stateRoundTripWithNulls() {
        val state = RemoteState(listingSeq = 3, query = "matrix", recursive = true, searching = true, selected = 2)
        assertEquals(ServerMessage.StateMsg(state), ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.StateMsg(state))))
        val withText = state.copy(message = "Permesso negato", nowPlaying = "a.mkv")
        assertEquals(ServerMessage.StateMsg(withText), ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.StateMsg(withText))))
    }

    @Test
    fun clientMessagesRoundTrip() {
        val messages = listOf(
            ClientMessage.Hello(1, "Pixel"),
            ClientMessage.Open(4, 10),
            ClientMessage.Select(4, 2),
            ClientMessage.Up,
            ClientMessage.Root,
            ClientMessage.Crumb(1),
            ClientMessage.Query("città"),
            ClientMessage.Recursive(true),
            ClientMessage.Key(RemoteKey.OK),
            ClientMessage.Refresh,
        )
        messages.forEach { assertEquals(it, ProtocolCodec.decodeClient(ProtocolCodec.encode(it))) }
    }

    @Test
    fun malformedOrUnknownMessagesAreIgnored() {
        assertNull(ProtocolCodec.decodeClient("non json"))
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"format_disk\"}"))
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"open\"}"))
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"key\",\"key\":\"power\"}"))
        assertNull(ProtocolCodec.decodeServer("[]"))
    }

    @Test
    fun queryIsTruncated() {
        val long = "x".repeat(1_000)
        val decoded = ProtocolCodec.decodeClient(ProtocolCodec.encode(ClientMessage.Query(long))) as ClientMessage.Query
        assertEquals(ProtocolCodec.MAX_QUERY_LENGTH, decoded.text.length)
    }
}
