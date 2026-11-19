package com.icarocomix.icaromanagertv.common

import com.icarocomix.icaromanagertv.common.atv.AtvRemoteProtocol
import com.icarocomix.icaromanagertv.common.atv.AtvRemoteProtocol.PairingStep
import com.icarocomix.icaromanagertv.common.atv.AtvRemoteProtocol.RemoteInbound
import com.icarocomix.icaromanagertv.common.atv.ProtoFraming
import com.icarocomix.icaromanagertv.common.atv.ProtoMessage
import com.icarocomix.icaromanagertv.common.atv.ProtoWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.math.BigInteger
import java.security.MessageDigest

class AtvRemoteProtocolTest {

    @Test
    fun pairingRequestHasTheExpectedWireFormat() {
        // Byte attesi calcolati a mano dalla specifica protobuf: versione 2, stato 200,
        // PairingRequest (campo 10) con service_name "s" e client_name "c".
        val expected = byteArrayOf(
            0x08, 0x02,
            0x10, 0xC8.toByte(), 0x01,
            0x52, 0x06, 0x0A, 0x01, 's'.code.toByte(), 0x12, 0x01, 'c'.code.toByte(),
        )
        assertArrayEquals(expected, AtvRemoteProtocol.pairingRequest("s", "c"))
    }

    @Test
    fun emptyNestedMessageIsPresent() {
        // La TV conferma la configurazione con un messaggio vuoto: conta la sua presenza.
        val ack = ProtoWriter().int(1, 2).int(2, 200).message(31) {}.toByteArray()
        assertEquals(AtvRemoteProtocol.PairingReply(200, PairingStep.CONFIGURATION_ACK), AtvRemoteProtocol.parsePairing(ack))
        val bad = ProtoWriter().int(1, 2).int(2, AtvRemoteProtocol.STATUS_BAD_SECRET).toByteArray()
        assertEquals(AtvRemoteProtocol.PairingReply(402, PairingStep.OTHER), AtvRemoteProtocol.parsePairing(bad))
    }

    @Test
    fun pairingStepsAreRecognised() {
        fun reply(field: Int) = AtvRemoteProtocol.parsePairing(ProtoWriter().int(2, 200).message(field) { string(1, "x") }.toByteArray()).step
        assertEquals(PairingStep.REQUEST_ACK, reply(11))
        assertEquals(PairingStep.OPTION, reply(20))
        assertEquals(PairingStep.SECRET_ACK, reply(41))
    }

    @Test
    fun protoRoundTripWithLargeValuesAndUnknownFields() {
        val data = ProtoWriter()
            .int(1, 300)
            .int(2, Long.MAX_VALUE)
            .string(3, "città")
            .message(4) { int(1, 7) }
            .toByteArray()
        val msg = ProtoMessage.parse(data)
        assertEquals(300L, msg.long(1))
        assertEquals(Long.MAX_VALUE, msg.long(2))
        assertEquals("città", msg.string(3))
        assertEquals(7L, msg.message(4)?.long(1))
        assertFalse(msg.has(5))
    }

    @Test
    fun framingRoundTripAndTruncation() {
        val payload = ByteArray(200) { it.toByte() }
        val framed = ProtoFraming.frame(payload)
        // 200 > 127: la lunghezza occupa due byte di varint.
        assertEquals(202, framed.size)
        assertArrayEquals(payload, ProtoFraming.read(ByteArrayInputStream(framed)))
        try {
            ProtoFraming.read(ByteArrayInputStream(framed.copyOf(50)))
            throw AssertionError("Attesa EOFException")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun remoteMessagesAreRecognised() {
        val ping = ProtoWriter().message(8) { int(1, 42) }.toByteArray()
        assertEquals(RemoteInbound.Ping(42), AtvRemoteProtocol.parseRemote(ping))
        val start = ProtoWriter().message(40) { int(1, 1) }.toByteArray()
        assertEquals(RemoteInbound.Start(true), AtvRemoteProtocol.parseRemote(start))
        val standby = ProtoWriter().message(40) {}.toByteArray()
        assertEquals(RemoteInbound.Start(false), AtvRemoteProtocol.parseRemote(standby))
        assertEquals(RemoteInbound.Configure, AtvRemoteProtocol.parseRemote(ProtoWriter().message(1) { int(1, 1) }.toByteArray()))
        assertEquals(RemoteInbound.SetActive, AtvRemoteProtocol.parseRemote(ProtoWriter().message(2) {}.toByteArray()))
        assertEquals(RemoteInbound.Error, AtvRemoteProtocol.parseRemote(ProtoWriter().message(3) { int(1, 1) }.toByteArray()))

        val link = ProtoMessage.parse(AtvRemoteProtocol.remoteAppLink("icaromanagertv://open"))
        assertEquals("icaromanagertv://open", link.message(90)?.string(1))
        val wake = ProtoMessage.parse(AtvRemoteProtocol.remoteKeyPress(AtvRemoteProtocol.KEYCODE_WAKEUP)).message(10)
        assertEquals(224L, wake?.long(1))
        assertEquals(3L, wake?.long(2))
        val pong = ProtoMessage.parse(AtvRemoteProtocol.remotePingResponse(42))
        assertEquals(42L, pong.message(9)?.long(1))
    }

    @Test
    fun pairingSecretBindsKeysAndCode() {
        val clientModulus = BigInteger("C3A1F0E5B7D2", 16).shiftLeft(2000).or(BigInteger.ONE)
        val serverModulus = BigInteger("A9B8C7D6E5F4", 16).shiftLeft(2000).or(BigInteger.TWO)
        val exponent = BigInteger.valueOf(65537)

        // Calcolo indipendente: byte senza segno (nessuno zero iniziale), esponente 01 00 01.
        fun unsigned(n: BigInteger) = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val tail = "4F2A"
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(unsigned(clientModulus))
            update(byteArrayOf(1, 0, 1))
            update(unsigned(serverModulus))
            update(byteArrayOf(1, 0, 1))
            update(byteArrayOf(0x4F, 0x2A))
            digest()
        }
        val code = "%02X".format(digest[0]) + tail

        val secret = AtvRemoteProtocol.pairingSecret(clientModulus, exponent, serverModulus, exponent, code.lowercase())
        assertNotNull(secret)
        assertArrayEquals(digest, secret)

        // Primo byte diverso: codice letto male, nessun segreto da inviare.
        val wrong = "%02X".format((digest[0] + 1) and 0xFF) + tail
        assertNull(AtvRemoteProtocol.pairingSecret(clientModulus, exponent, serverModulus, exponent, wrong))
    }

    @Test
    fun codeNormalisation() {
        assertEquals("0A1B2C", AtvRemoteProtocol.normalizeCode(" 0a1b2c "))
        assertNull(AtvRemoteProtocol.normalizeCode("0A1B2"))
        assertNull(AtvRemoteProtocol.normalizeCode("0A1B2G"))
        assertTrue(AtvRemoteProtocol.normalizeCode("ABCDEF") != null)
    }
}
