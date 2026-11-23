package com.icarocomix.icaromanagertv.common

import com.icarocomix.icaromanagertv.common.files.FileNames
import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.FileOpKind
import com.icarocomix.icaromanagertv.common.protocol.ProtocolCodec
import com.icarocomix.icaromanagertv.common.protocol.RemoteState
import com.icarocomix.icaromanagertv.common.protocol.ServerMessage
import com.icarocomix.icaromanagertv.common.protocol.TvStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messaggi della v1.9 (sincronizzazione, scroll, operazioni sui file) e regole sui nomi. */
class FileOpsProtocolTest {

    @Test
    fun statusRoundTrip() {
        val status = TvStatus(
            synced = false,
            clipCount = 1,
            clipName = "Film – 2024.mkv",
            clipMove = true,
            opText = "Copia di «Film.mkv»",
            opPermille = 437,
            opCancelable = true,
        )
        assertEquals(ServerMessage.StatusMsg(status), ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.StatusMsg(status))))
        assertEquals(
            ServerMessage.StatusMsg(TvStatus.DEFAULT),
            ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.StatusMsg(TvStatus.DEFAULT))),
        )
    }

    @Test
    fun statusOutOfRangeProgressBecomesIndeterminate() {
        val decoded = ProtocolCodec.decodeServer("""{"t":"status","op":"x","opP":5000}""") as ServerMessage.StatusMsg
        assertEquals(-1, decoded.status.opPermille)
        // Campi assenti: sincronizzazione attiva, appunti vuoti.
        assertTrue(decoded.status.synced)
        assertEquals(0, decoded.status.clipCount)
    }

    @Test
    fun stateCarriesScrollTopAndDefaultsWhenMissing() {
        val state = RemoteState(listingSeq = 4, selected = 2, scrollTop = 37)
        assertEquals(ServerMessage.StateMsg(state), ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.StateMsg(state))))
        val old = ProtocolCodec.decodeServer("""{"t":"state","seq":1,"query":"","selected":0}""") as ServerMessage.StateMsg
        assertEquals(-1, old.state.scrollTop)
    }

    @Test
    fun clientMessagesRoundTrip() {
        val messages = listOf(
            ClientMessage.Sync(false),
            ClientMessage.Sync(true),
            ClientMessage.Scroll(12, 340),
            ClientMessage.FileOp(FileOpKind.COPY, 3, 7),
            ClientMessage.FileOp(FileOpKind.CUT, 3, 8),
            ClientMessage.FileOp(FileOpKind.PASTE),
            ClientMessage.FileOp(FileOpKind.DELETE, 3, 1),
            ClientMessage.FileOp(FileOpKind.RENAME, 3, 1, "Nuovo nome.mp4"),
            ClientMessage.FileOp(FileOpKind.MKDIR, name = "Serie TV"),
            ClientMessage.FileOp(FileOpKind.CANCEL),
            ClientMessage.FileOp(FileOpKind.CLEAR),
        )
        messages.forEach { assertEquals(it, ProtocolCodec.decodeClient(ProtocolCodec.encode(it))) }
    }

    @Test
    fun incompleteFileOpsAreDiscarded() {
        assertNull(ProtocolCodec.decodeClient("""{"t":"fop","op":"delete"}"""))
        assertNull(ProtocolCodec.decodeClient("""{"t":"fop","op":"rename","seq":1,"index":2}"""))
        assertNull(ProtocolCodec.decodeClient("""{"t":"fop","op":"mkdir","name":"  "}"""))
        assertNull(ProtocolCodec.decodeClient("""{"t":"fop","op":"format","seq":1,"index":2}"""))
    }

    @Test
    fun nameValidation() {
        assertEquals(FileNames.Check.Valid("Film.mkv"), FileNames.check("  Film.mkv  "))
        assertEquals(FileNames.Check.Valid("Cartella"), FileNames.check("Cartella..."))
        assertEquals(FileNames.Check.Invalid(FileNames.Problem.EMPTY), FileNames.check("   "))
        assertEquals(FileNames.Check.Invalid(FileNames.Problem.RESERVED), FileNames.check(".."))
        assertEquals(FileNames.Check.Invalid(FileNames.Problem.FORBIDDEN_CHAR), FileNames.check("a/b"))
        assertEquals(FileNames.Check.Invalid(FileNames.Problem.FORBIDDEN_CHAR), FileNames.check("a:b"))
        assertEquals(FileNames.Check.Invalid(FileNames.Problem.FORBIDDEN_CHAR), FileNames.check("riga\nnuova"))
        // 128 caratteri accentati = 256 byte UTF-8: oltre il limite anche se sotto i 255 caratteri.
        assertEquals(FileNames.Check.Invalid(FileNames.Problem.TOO_LONG), FileNames.check("à".repeat(128)))
        assertEquals("Città", FileNames.validOrNull("Città"))
    }

    @Test
    fun uniqueNames() {
        assertEquals("Film.mkv", FileNames.unique("Film.mkv", listOf("Altro.mkv")))
        assertEquals("Film (1).mkv", FileNames.unique("Film.mkv", listOf("film.MKV")))
        assertEquals("Film (2).mkv", FileNames.unique("Film.mkv", listOf("Film.mkv", "Film (1).mkv")))
        assertEquals("Film (2).mkv", FileNames.unique("Film (1).mkv", listOf("Film.mkv", "Film (1).mkv")))
        assertEquals("Serie (1)", FileNames.unique("Serie", listOf("Serie")))
        assertEquals(".config (1)", FileNames.unique(".config", listOf(".config")))
        assertEquals("archivio (1).tar.gz", FileNames.unique("archivio.tar.gz", listOf("archivio.tar.gz")))
    }
}
