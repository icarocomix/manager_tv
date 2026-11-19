package com.icarocomix.icaromanagertv.common

import com.icarocomix.icaromanagertv.common.protocol.ClientMessage
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import com.icarocomix.icaromanagertv.common.protocol.PlaybackState
import com.icarocomix.icaromanagertv.common.protocol.PlayerAction
import com.icarocomix.icaromanagertv.common.protocol.ProtocolCodec
import com.icarocomix.icaromanagertv.common.protocol.ServerMessage
import com.icarocomix.icaromanagertv.common.protocol.TvView
import com.icarocomix.icaromanagertv.common.protocol.UiCommand
import com.icarocomix.icaromanagertv.common.util.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProtocolTest {

    @Test
    fun playbackStateRoundTrip() {
        val state = PlaybackState(
            active = true,
            title = "Episodio 02 – Città.mkv",
            kind = EntryKind.VIDEO,
            positionMs = 3_723_456,
            durationMs = 5_400_000,
            playing = true,
            buffering = false,
            index = 1,
            count = 12,
            autoplay = false,
            view = TvView.MINI,
            resumeFromMs = 1_200_000,
            volume = 7,
            volumeMax = 15,
            muted = true,
            subtitles = true,
        )
        assertEquals(ServerMessage.PlaybackMsg(state), ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.PlaybackMsg(state))))
        assertEquals(ServerMessage.PlaybackMsg(PlaybackState.IDLE), ProtocolCodec.decodeServer(ProtocolCodec.encode(ServerMessage.PlaybackMsg(PlaybackState.IDLE))))
    }

    @Test
    fun playlistFlags() {
        val first = PlaybackState(active = true, index = 0, count = 3)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)
        val last = first.copy(index = 2)
        assertTrue(last.hasPrevious)
        assertFalse(last.hasNext)
        assertFalse(PlaybackState.IDLE.copy(resumeFromMs = 10).resumePending)
        assertTrue(first.copy(resumeFromMs = 0).resumePending)
    }

    @Test
    fun playerClientMessagesRoundTrip() {
        val messages = PlayerAction.entries.map { ClientMessage.Player(it) } + listOf(
            ClientMessage.SeekTo(90_000),
            ClientMessage.Autoplay(true),
            ClientMessage.Autoplay(false),
            ClientMessage.Resume(true),
            ClientMessage.Resume(false),
            ClientMessage.Layout(TvView.MINI),
            ClientMessage.Layout(TvView.FULLSCREEN),
            ClientMessage.Volume(0),
            ClientMessage.Volume(12),
        )
        messages.forEach { assertEquals(it, ProtocolCodec.decodeClient(ProtocolCodec.encode(it))) }
    }

    @Test
    fun malformedPlayerMessagesAreIgnoredOrClamped() {
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"player\",\"a\":\"eject\"}"))
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"view\",\"view\":\"pip3d\"}"))
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"seek\"}"))
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"resume\"}"))
        assertEquals(ClientMessage.SeekTo(0), ProtocolCodec.decodeClient("{\"t\":\"seek\",\"pos\":-500}"))
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"volume\"}"))
        assertEquals(ClientMessage.Volume(0), ProtocolCodec.decodeClient("{\"t\":\"volume\",\"level\":-3}"))
    }

    @Test
    fun volumeStateFromOlderServer() {
        // Server v1.3: il messaggio playback non ha i campi del volume.
        val old = "{\"t\":\"playback\",\"active\":true,\"pos\":1000,\"dur\":5000}"
        val decoded = (ProtocolCodec.decodeServer(old) as ServerMessage.PlaybackMsg).playback
        assertEquals(-1, decoded.volume)
        assertFalse(decoded.volumeAdjustable)
        assertFalse(decoded.muted)
        assertTrue(PlaybackState(volume = 0, volumeMax = 15).volumeAdjustable)
        assertFalse(PlaybackState(volume = 16, volumeMax = 15).volumeAdjustable)
        assertTrue(PlayerAction.MUTE.isVolume)
        assertFalse(PlayerAction.NEXT.isVolume)
        // Server precedente alla v1.6: senza il campo dei sottotitoli il telefono li considera assenti.
        assertFalse(decoded.subtitles)
    }

    @Test
    fun uiCommandsRoundTrip() {
        UiCommand.entries.forEach { command ->
            val message = ClientMessage.Ui(command)
            assertEquals(message, ProtocolCodec.decodeClient(ProtocolCodec.encode(message)))
        }
        // Comando sconosciuto (client più recente) o assente: scartato, mai un'eccezione.
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"ui\",\"cmd\":\"reboot\"}"))
        assertNull(ProtocolCodec.decodeClient("{\"t\":\"ui\"}"))
        assertTrue(UiCommand.PLAYER_SUBTITLES.isPlayerPanel)
        assertFalse(UiCommand.BRING_TO_FRONT.isPlayerPanel)
        assertFalse(UiCommand.HOME.isPlayerPanel)
    }

    @Test
    fun timeFormat() {
        assertEquals("0:00", TimeFormat.format(0))
        assertEquals("0:59", TimeFormat.format(59_999))
        assertEquals("12:05", TimeFormat.format(725_000))
        assertEquals("1:02:03", TimeFormat.format(3_723_000))
        assertEquals("--:--", TimeFormat.format(-1))
    }
}
