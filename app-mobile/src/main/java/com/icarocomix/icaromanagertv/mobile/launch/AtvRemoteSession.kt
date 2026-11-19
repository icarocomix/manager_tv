package com.icarocomix.icaromanagertv.mobile.launch

import android.os.SystemClock
import android.util.Log
import com.icarocomix.icaromanagertv.common.atv.AtvRemoteProtocol
import com.icarocomix.icaromanagertv.common.atv.AtvRemoteProtocol.PairingStep
import com.icarocomix.icaromanagertv.common.atv.AtvRemoteProtocol.RemoteInbound
import com.icarocomix.icaromanagertv.common.atv.ProtoFraming
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Esito negativo di un'operazione verso il servizio di controllo remoto della TV. */
class AtvException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** Nessuna risposta: TV spenta, in avvio, o rete diversa. Si può riprovare. */
        UNREACHABLE,

        /** La TV risponde ma la porta è chiusa: servizio non ancora avviato o assente. */
        REFUSED,

        /** La TV non riconosce il telefono: accoppiamento mai fatto o cancellato sulla TV. */
        NOT_RECOGNISED,

        /** Il certificato della TV non è quello dell'accoppiamento. */
        CERT_CHANGED,

        /** Codice digitato male (verificato sul telefono): la stessa sessione resta valida. */
        BAD_CODE,

        /** La TV ha rifiutato il codice: serve un nuovo codice (nuova sessione). */
        CODE_REJECTED,

        /** Risposta inattesa o interrotta. */
        PROTOCOL,
    }

    val retryable: Boolean get() = reason == Reason.UNREACHABLE || reason == Reason.REFUSED
}

/** Apertura dei socket TLS verso la TV con traduzione degli errori di rete in [AtvException]. */
internal object AtvSockets {

    private const val TAG = "AtvSockets"
    private const val CONNECT_TIMEOUT_MS = 4_000
    const val READ_TIMEOUT_MS = 6_000

    fun open(host: String, port: Int, context: SSLContext): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            raw.closeQuietly()
            // ECONNREFUSED: l'host c'è ma nessuno ascolta (servizio non ancora partito o
            // assente). Il resto (timeout, host irraggiungibile) vale "TV non in rete".
            val refused = e is ConnectException && e.message?.contains("ECONNREFUSED") == true
            throw AtvException(
                if (refused) AtvException.Reason.REFUSED else AtvException.Reason.UNREACHABLE,
                "$host:$port non raggiungibile (${e.message})",
                e,
            )
        }
        val socket = try {
            context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        } catch (e: IOException) {
            raw.closeQuietly()
            throw AtvException(AtvException.Reason.PROTOCOL, "Canale sicuro non creato: ${e.message}", e)
        }
        socket.soTimeout = READ_TIMEOUT_MS
        try {
            socket.startHandshake()
        } catch (e: SSLException) {
            socket.closeQuietly()
            throw translateTls(e)
        } catch (e: IOException) {
            socket.closeQuietly()
            throw AtvException(AtvException.Reason.UNREACHABLE, "Collegamento con la TV interrotto: ${e.message}", e)
        }
        Log.i(TAG, "Canale sicuro con $host:$port aperto (${socket.session.protocol})")
        return socket
    }

    /** Errore TLS: certificato della TV cambiato o, più spesso, telefono non riconosciuto. */
    fun translateTls(e: Exception): AtvException {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ServerCertificateChangedException) {
                return AtvException(AtvException.Reason.CERT_CHANGED, cause.message ?: "certificato cambiato", e)
            }
            cause = cause.cause
        }
        return AtvException(AtvException.Reason.NOT_RECOGNISED, "La TV ha rifiutato il collegamento sicuro: ${e.message}", e)
    }

    fun write(output: OutputStream, message: ByteArray) {
        output.write(ProtoFraming.frame(message))
        output.flush()
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }
}

/**
 * Accoppiamento con la TV (porta 6467). [begin] arriva fino al punto in cui la TV mostra il
 * codice; [finish] invia il segreto calcolato dal codice digitato. Bloccante: va usato fuori dal
 * main thread. La sessione resta aperta tra le due chiamate (l'utente legge e digita il codice).
 */
class AtvPairing(private val identity: AtvIdentity, val host: String) : Closeable {

    @Volatile private var socket: SSLSocket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var serverKey: RSAPublicKey

    /** Impronta del certificato della TV, da fissare per i collegamenti successivi. */
    lateinit var serverFingerprint: String
        private set

    fun begin(clientName: String) {
        val s = AtvSockets.open(host, AtvRemoteProtocol.PAIRING_PORT, identity.sslContext(null))
        socket = s
        input = BufferedInputStream(s.inputStream)
        output = s.outputStream
        val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw AtvException(AtvException.Reason.PROTOCOL, "La TV non ha presentato un certificato")
        serverKey = cert.publicKey as? RSAPublicKey
            ?: throw AtvException(AtvException.Reason.PROTOCOL, "Chiave della TV di tipo inatteso (${cert.publicKey.algorithm})")
        serverFingerprint = AtvIdentity.fingerprint(cert)

        send(AtvRemoteProtocol.pairingRequest(AtvRemoteProtocol.SERVICE_NAME, clientName))
        expect(PairingStep.REQUEST_ACK)
        send(AtvRemoteProtocol.pairingOption())
        expect(PairingStep.OPTION)
        send(AtvRemoteProtocol.pairingConfiguration())
        // Da qui la TV mostra il codice a schermo; mentre l'utente lo digita il canale resta
        // inattivo, quindi tolgo il timeout di lettura per la risposta successiva.
        expect(PairingStep.CONFIGURATION_ACK)
    }

    /** @return l'impronta del certificato della TV, ad accoppiamento riuscito. */
    fun finish(code: String): String {
        val client = identity.publicKey()
        val secret = AtvRemoteProtocol.pairingSecret(
            client.modulus,
            client.publicExponent,
            serverKey.modulus,
            serverKey.publicExponent,
            code,
        ) ?: throw AtvException(AtvException.Reason.BAD_CODE, "Il codice non corrisponde a quello mostrato dalla TV")
        send(AtvRemoteProtocol.pairingSecret(secret))
        val reply = read()
        if (reply.status == AtvRemoteProtocol.STATUS_BAD_SECRET) {
            throw AtvException(AtvException.Reason.CODE_REJECTED, "La TV ha rifiutato il codice")
        }
        if (reply.status != AtvRemoteProtocol.STATUS_OK || reply.step != PairingStep.SECRET_ACK) {
            throw AtvException(AtvException.Reason.CODE_REJECTED, "Accoppiamento non concluso (stato ${reply.status})")
        }
        return serverFingerprint
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun send(message: ByteArray) {
        try {
            AtvSockets.write(output, message)
        } catch (e: IOException) {
            throw AtvException(AtvException.Reason.PROTOCOL, "Invio alla TV non riuscito: ${e.message}", e)
        }
    }

    private fun read(): AtvRemoteProtocol.PairingReply = try {
        AtvRemoteProtocol.parsePairing(ProtoFraming.read(input))
    } catch (e: SSLException) {
        throw AtvSockets.translateTls(e)
    } catch (e: SocketTimeoutException) {
        throw AtvException(AtvException.Reason.PROTOCOL, "La TV non ha risposto all'accoppiamento", e)
    } catch (e: IOException) {
        throw AtvException(AtvException.Reason.PROTOCOL, "Accoppiamento interrotto dalla TV: ${e.message}", e)
    }

    private fun expect(step: PairingStep) {
        val reply = read()
        if (reply.status != AtvRemoteProtocol.STATUS_OK) {
            throw AtvException(AtvException.Reason.PROTOCOL, "La TV ha risposto con lo stato ${reply.status} all'accoppiamento")
        }
        if (reply.step != step) {
            throw AtvException(AtvException.Reason.PROTOCOL, "Risposta inattesa della TV (${reply.step} invece di $step)")
        }
        if (step == PairingStep.CONFIGURATION_ACK) socket?.soTimeout = 0
    }
}

/**
 * Canale dei comandi (porta 6466): presentazione del telefono, attivazione della sessione e
 * richiesta di apertura di un link. Il servizio è del sistema della TV: risponde anche con
 * IcaroManagerTV chiusa o mai avviata.
 */
class AtvLauncher(private val identity: AtvIdentity) {

    data class DeviceInfo(val model: String, val vendor: String, val appVersion: String)

    /**
     * Apre [link] sulla TV [host]. Se la TV segnala di essere in standby la risveglio prima.
     * Bloccante: va usato fuori dal main thread.
     */
    fun openLink(host: String, pinnedServerSha256: String, link: String, device: DeviceInfo) {
        val socket = AtvSockets.open(host, AtvRemoteProtocol.REMOTE_PORT, identity.sslContext(pinnedServerSha256))
        socket.use { s ->
            val input = BufferedInputStream(s.inputStream)
            val output = s.outputStream
            val started = handshake(s, input, output, device)
            if (started == false) {
                Log.i(TAG, "TV in standby: la risveglio prima di aprire l'app")
                AtvSockets.write(output, AtvRemoteProtocol.remoteKeyPress(AtvRemoteProtocol.KEYCODE_WAKEUP))
            }
            AtvSockets.write(output, AtvRemoteProtocol.remoteAppLink(link))
            Log.i(TAG, "Richiesta di apertura di $link inviata a $host")
            // Breve attesa di un eventuale rifiuto; il silenzio vale come accettazione.
            s.soTimeout = AFTER_LINK_WAIT_MS
            val until = SystemClock.elapsedRealtime() + AFTER_LINK_WAIT_MS
            try {
                while (SystemClock.elapsedRealtime() < until) {
                    when (val msg = AtvRemoteProtocol.parseRemote(ProtoFraming.read(input))) {
                        is RemoteInbound.Ping -> AtvSockets.write(output, AtvRemoteProtocol.remotePingResponse(msg.value))
                        RemoteInbound.Error -> throw AtvException(AtvException.Reason.PROTOCOL, "La TV ha rifiutato l'apertura dell'app")
                        else -> Unit
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: EOFException) {
            }
        }
    }

    /**
     * La TV si presenta, chiede la configurazione del telefono e poi l'attivazione; di solito
     * comunica subito dopo se è accesa. Ritorno quando la sessione è attiva: con lo stato di
     * accensione se arriva entro poco, altrimenti null.
     */
    private fun handshake(s: SSLSocket, input: InputStream, output: OutputStream, device: DeviceInfo): Boolean? {
        val deadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
        var configured = false
        var activeAt = 0L
        var started: Boolean? = null
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (activeAt > 0 && (started != null || now - activeAt >= START_WAIT_MS)) return started
            if (now > deadline) throw AtvException(AtvException.Reason.PROTOCOL, "La TV non ha completato la presentazione")
            s.soTimeout = if (activeAt > 0) START_WAIT_MS else AtvSockets.READ_TIMEOUT_MS
            val frame = try {
                ProtoFraming.read(input)
            } catch (e: SocketTimeoutException) {
                if (activeAt > 0) return started
                throw AtvException(AtvException.Reason.PROTOCOL, "La TV non risponde sul canale dei comandi", e)
            } catch (e: IOException) {
                // Con TLS 1.3 il rifiuto del certificato del telefono arriva alla prima lettura,
                // come errore TLS o chiusura del canale prima ancora della presentazione.
                if (!configured) throw AtvSockets.translateTls(e)
                throw AtvException(AtvException.Reason.PROTOCOL, "Canale dei comandi interrotto: ${e.message}", e)
            }
            when (val msg = AtvRemoteProtocol.parseRemote(frame)) {
                RemoteInbound.Configure -> {
                    AtvSockets.write(output, AtvRemoteProtocol.remoteConfigure(device.model, device.vendor, device.appVersion))
                    configured = true
                }
                RemoteInbound.SetActive -> {
                    AtvSockets.write(output, AtvRemoteProtocol.remoteSetActive())
                    if (activeAt == 0L) activeAt = SystemClock.elapsedRealtime()
                }
                is RemoteInbound.Ping -> AtvSockets.write(output, AtvRemoteProtocol.remotePingResponse(msg.value))
                is RemoteInbound.Start -> started = msg.started
                RemoteInbound.Error -> throw AtvException(AtvException.Reason.PROTOCOL, "La TV ha rifiutato la configurazione del telefono")
                RemoteInbound.Other -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "AtvLauncher"
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val START_WAIT_MS = 1_500
        const val AFTER_LINK_WAIT_MS = 800
    }
}
