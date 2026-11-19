package com.icarocomix.icaromanagertv.common.atv

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Messaggi del servizio di controllo remoto di Android TV ("Android TV Remote Service", protocollo
 * v2), lo stesso usato dall'app Google TV e dai telecomandi virtuali del telefono. È un servizio
 * di sistema della TV, attivo dall'accensione e indipendente dalle app installate: permette al
 * telefono di aprire IcaroManagerTV anche quando questa non è in esecuzione o non è mai stata
 * avviata.
 *
 * Due canali TLS con certificato client:
 * - accoppiamento (porta [PAIRING_PORT]), una tantum: la TV mostra un codice esadecimale di 6
 *   caratteri, il telefono dimostra di averlo letto inviando un hash che lega codice e chiavi
 *   pubbliche di entrambi; da allora la TV riconosce il certificato del telefono;
 * - comandi (porta [REMOTE_PORT]): breve scambio di configurazione, poi richiesta di apertura di
 *   un link ([remoteAppLink]).
 */
object AtvRemoteProtocol {

    const val PAIRING_PORT = 6467
    const val REMOTE_PORT = 6466

    /** Tipo di servizio mDNS annunciato dalle TV con il servizio di controllo remoto v2. */
    const val SERVICE_TYPE = "_androidtvremote2._tcp."

    // ------------------------------------------------------------------ accoppiamento

    private const val PAIRING_VERSION = 2L
    const val STATUS_OK = 200L
    const val STATUS_ERROR = 400L
    const val STATUS_BAD_CONFIGURATION = 401L
    const val STATUS_BAD_SECRET = 402L

    private const val ENCODING_HEXADECIMAL = 3L
    private const val CODE_LENGTH = 6L
    private const val ROLE_INPUT = 1L

    // Campi di PairingMessage.
    private const val F_PROTOCOL_VERSION = 1
    private const val F_STATUS = 2
    private const val F_PAIRING_REQUEST = 10
    private const val F_PAIRING_REQUEST_ACK = 11
    private const val F_PAIRING_OPTION = 20
    private const val F_PAIRING_CONFIGURATION = 30
    private const val F_PAIRING_CONFIGURATION_ACK = 31
    private const val F_PAIRING_SECRET = 40
    private const val F_PAIRING_SECRET_ACK = 41

    enum class PairingStep { REQUEST_ACK, OPTION, CONFIGURATION_ACK, SECRET_ACK, OTHER }

    data class PairingReply(val status: Long, val step: PairingStep)

    private fun pairingMessage(block: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().int(F_PROTOCOL_VERSION, PAIRING_VERSION).int(F_STATUS, STATUS_OK).apply(block).toByteArray()

    /** Primo messaggio: chi sono ([clientName] compare sulla TV durante l'accoppiamento). */
    fun pairingRequest(serviceName: String, clientName: String): ByteArray = pairingMessage {
        message(F_PAIRING_REQUEST) {
            string(1, serviceName)
            string(2, clientName)
        }
    }

    /** Ruolo "input" (il telefono digita il codice) con codifica esadecimale a 6 simboli. */
    fun pairingOption(): ByteArray = pairingMessage {
        message(F_PAIRING_OPTION) {
            message(1) { int(1, ENCODING_HEXADECIMAL).int(2, CODE_LENGTH) }
            int(3, ROLE_INPUT)
        }
    }

    fun pairingConfiguration(): ByteArray = pairingMessage {
        message(F_PAIRING_CONFIGURATION) {
            message(1) { int(1, ENCODING_HEXADECIMAL).int(2, CODE_LENGTH) }
            int(2, ROLE_INPUT)
        }
    }

    fun pairingSecret(secret: ByteArray): ByteArray = pairingMessage {
        message(F_PAIRING_SECRET) { bytes(1, secret) }
    }

    fun parsePairing(data: ByteArray): PairingReply {
        val msg = ProtoMessage.parse(data)
        val step = when {
            msg.has(F_PAIRING_REQUEST_ACK) -> PairingStep.REQUEST_ACK
            msg.has(F_PAIRING_OPTION) -> PairingStep.OPTION
            msg.has(F_PAIRING_CONFIGURATION_ACK) -> PairingStep.CONFIGURATION_ACK
            msg.has(F_PAIRING_SECRET_ACK) -> PairingStep.SECRET_ACK
            else -> PairingStep.OTHER
        }
        return PairingReply(msg.long(F_STATUS), step)
    }

    /** Codice mostrato dalla TV in forma canonica (6 cifre esadecimali maiuscole), o null. */
    fun normalizeCode(code: String): String? =
        code.trim().uppercase().takeIf { it.length == CODE_LENGTH.toInt() && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }

    /**
     * Segreto dell'accoppiamento: SHA-256 di modulo ed esponente RSA del telefono, modulo ed
     * esponente della TV (byte senza segno, senza zeri iniziali) e degli ultimi 4 caratteri del
     * codice. I primi 2 caratteri del codice sono il primo byte dell'hash: lo verifico qui, così
     * un codice digitato male si segnala subito senza bruciare il tentativo sulla TV.
     *
     * @return l'hash da inviare, o null se il codice non corrisponde a queste chiavi.
     */
    fun pairingSecret(
        clientModulus: BigInteger,
        clientExponent: BigInteger,
        serverModulus: BigInteger,
        serverExponent: BigInteger,
        code: String,
    ): ByteArray? {
        val normalized = normalizeCode(code) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(unsigned(clientModulus))
            update(unsigned(clientExponent))
            update(unsigned(serverModulus))
            update(unsigned(serverExponent))
            update(hexToBytes(normalized.substring(2)))
            digest()
        }
        val check = normalized.substring(0, 2).toInt(16).toByte()
        return digest.takeIf { it[0] == check }
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    // ------------------------------------------------------------------ comandi

    // Campi di RemoteMessage.
    private const val F_REMOTE_CONFIGURE = 1
    private const val F_REMOTE_SET_ACTIVE = 2
    private const val F_REMOTE_ERROR = 3
    private const val F_REMOTE_PING_REQUEST = 8
    private const val F_REMOTE_PING_RESPONSE = 9
    private const val F_REMOTE_KEY_INJECT = 10
    private const val F_REMOTE_START = 40
    private const val F_REMOTE_APP_LINK = 90

    /**
     * Funzioni dichiarate dal telefono nella configurazione (maschera di bit del protocollo):
     * ping, tasti, IME, voce, accensione, volume e apertura di link. È il valore usato dai
     * client esistenti; la TV lo rimanda in [remoteSetActive].
     */
    private const val FEATURES = 622L

    /** Direzione "pressione breve" di RemoteKeyInject. */
    private const val DIRECTION_SHORT = 3L

    /** KEYCODE_WAKEUP: accende lo schermo se la TV è in standby, senza effetto se è già accesa. */
    const val KEYCODE_WAKEUP = 224

    sealed class RemoteInbound {
        /** La TV si presenta e chiede la configurazione del telefono. */
        data object Configure : RemoteInbound()

        /** La TV chiede di attivare la sessione. */
        data object SetActive : RemoteInbound()

        data class Ping(val value: Long) : RemoteInbound()

        /** Stato della TV: [started] false se è in standby. */
        data class Start(val started: Boolean) : RemoteInbound()

        /** La TV ha rifiutato l'ultimo comando. */
        data object Error : RemoteInbound()

        /** Messaggi non rilevanti per l'apertura dell'app (volume, app in primo piano…). */
        data object Other : RemoteInbound()
    }

    fun parseRemote(data: ByteArray): RemoteInbound {
        val msg = ProtoMessage.parse(data)
        return when {
            msg.has(F_REMOTE_CONFIGURE) -> RemoteInbound.Configure
            msg.has(F_REMOTE_SET_ACTIVE) -> RemoteInbound.SetActive
            msg.has(F_REMOTE_PING_REQUEST) -> RemoteInbound.Ping(msg.message(F_REMOTE_PING_REQUEST)?.long(1) ?: 0)
            msg.has(F_REMOTE_START) -> RemoteInbound.Start(msg.message(F_REMOTE_START)?.long(1) == 1L)
            msg.has(F_REMOTE_ERROR) -> RemoteInbound.Error
            else -> RemoteInbound.Other
        }
    }

    fun remoteConfigure(model: String, vendor: String, appVersion: String): ByteArray =
        ProtoWriter().message(F_REMOTE_CONFIGURE) {
            int(1, FEATURES)
            message(2) {
                string(1, model)
                string(2, vendor)
                int(3, 1)
                string(4, "1")
                string(5, SERVICE_NAME)
                string(6, appVersion)
            }
        }.toByteArray()

    fun remoteSetActive(): ByteArray = ProtoWriter().message(F_REMOTE_SET_ACTIVE) { int(1, FEATURES) }.toByteArray()

    fun remotePingResponse(value: Long): ByteArray =
        ProtoWriter().message(F_REMOTE_PING_RESPONSE) { int(1, value) }.toByteArray()

    /** Pressione breve di un tasto del telecomando (codici `KeyEvent` di Android). */
    fun remoteKeyPress(keyCode: Int): ByteArray =
        ProtoWriter().message(F_REMOTE_KEY_INJECT) { int(1, keyCode.toLong()).int(2, DIRECTION_SHORT) }.toByteArray()

    /** Chiede alla TV di aprire [link] (deep link o `market://launch?id=…`). */
    fun remoteAppLink(link: String): ByteArray =
        ProtoWriter().message(F_REMOTE_APP_LINK) { string(1, link) }.toByteArray()

    /** Nome di servizio con cui il telefono si presenta alla TV. */
    const val SERVICE_NAME = "com.icarocomix.icaromanagertv.mobile"
}
