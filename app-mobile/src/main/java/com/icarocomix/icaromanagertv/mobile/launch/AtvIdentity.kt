package com.icarocomix.icaromanagertv.mobile.launch

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Identità del telefono verso il servizio di controllo remoto di Android TV: coppia di chiavi RSA
 * con certificato autofirmato, generata una volta sola nell'AndroidKeyStore (la chiave privata
 * non esce mai dall'hardware sicuro del telefono). La TV la associa al telefono durante
 * l'accoppiamento e da allora accetta i comandi solo da chi la presenta.
 */
class AtvIdentity {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Certificato del telefono, creato al primo uso. */
    @Synchronized
    fun certificate(): X509Certificate {
        (keyStore.getCertificate(ALIAS) as? X509Certificate)?.let { return it }
        generate()
        return keyStore.getCertificate(ALIAS) as X509Certificate
    }

    fun publicKey(): RSAPublicKey = certificate().publicKey as RSAPublicKey

    private fun privateKey(): PrivateKey {
        certificate()
        return keyStore.getKey(ALIAS, null) as PrivateKey
    }

    /**
     * Contesto TLS con il certificato del telefono come certificato client.
     *
     * Il certificato della TV è autofirmato, quindi non posso validarlo con le autorità
     * di certificazione. Durante l'accoppiamento ([pinnedServerSha256] null) lo accetto: è il
     * codice mostrato dalla TV a garantire che dall'altra parte ci sia proprio lei, perché il
     * segreto inviato lega codice e chiave pubblica della TV. Dopo l'accoppiamento ne fisso
     * l'impronta SHA-256 e rifiuto qualunque altro certificato.
     */
    fun sslContext(pinnedServerSha256: String?): SSLContext {
        val key = privateKey()
        val chain = arrayOf(certificate())
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
            override fun getPrivateKey(alias: String?): PrivateKey = key
        }
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Il telefono non accetta connessioni in ingresso")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("La TV non ha presentato un certificato")
                if (pinnedServerSha256 != null && fingerprint(leaf) != pinnedServerSha256) {
                    throw ServerCertificateChangedException()
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trustManager), null) }
    }

    private fun generate() {
        val now = System.currentTimeMillis()
        // Firma (TLS 1.2 e 1.3, PKCS#1 e PSS) e RSA "grezzo": a seconda della versione di TLS
        // negoziata la libreria TLS di Android usa l'una o l'altra operazione sulla chiave.
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setRandomizedEncryptionRequired(false)
            .setCertificateSubject(X500Principal("CN=Manager TV Remote"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - TimeUnit.DAYS.toMillis(1)))
            .setCertificateNotAfter(Date(now + TimeUnit.DAYS.toMillis(365L * 30)))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        Log.i(TAG, "Generata l'identità del telefono per il controllo remoto della TV")
    }

    companion object {
        private const val TAG = "AtvIdentity"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "icaromanagertv-atv-remote"

        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    }
}

/** Il certificato della TV non è quello registrato all'accoppiamento (TV ripristinata o altro dispositivo). */
class ServerCertificateChangedException : CertificateException("Il certificato della TV non corrisponde a quello dell'abbinamento")
