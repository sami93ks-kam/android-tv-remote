package com.example.xiaomiremote.protocol

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal data class ClientIdentity(val certificate: X509Certificate, val privateKey: PrivateKey)

internal object CryptoUtils {
    val pinPattern = Regex("[0-9A-Fa-f]{6}")

    fun createIdentity(): ClientIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=atvremote")
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(159, SecureRandom()).setBit(158),
            Date(now - day), Date(now + 3650 * day), subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return ClientIdentity(cert, keyPair.private)
    }

    fun publicKeyFingerprint(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    fun createSSLContext(identity: ClientIdentity, peerFingerprint: ByteArray?): SSLContext {
        val password = CharArray(0)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", identity.privateKey, password, arrayOf(identity.certificate))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, password)
        }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                throw CertificateException("Unexpected client certificate")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: throw CertificateException("Missing TV certificate")
                // On first pairing the displayed code authenticates both public keys.
                if (peerFingerprint != null &&
                    !MessageDigest.isEqual(peerFingerprint, publicKeyFingerprint(cert))
                ) throw PeerChangedException()
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(trust), null) }
    }

    fun computePairingSecret(client: X509Certificate, server: X509Certificate, pin: String): ByteArray? {
        if (!pin.matches(pinPattern)) return null
        val clientKey = client.publicKey as RSAPublicKey
        val serverKey = server.publicKey as? RSAPublicKey
            ?: throw CertificateException("TV pairing requires an RSA certificate")
        val digest = MessageDigest.getInstance("SHA-256")
        for (key in listOf(clientKey, serverKey)) {
            digest.update(key.modulus.unsignedBytes())
            digest.update(key.publicExponent.unsignedBytes())
        }
        digest.update(pin.substring(2, 4).toInt(16).toByte())
        digest.update(pin.substring(4, 6).toInt(16).toByte())
        return digest.digest().takeIf { it[0] == pin.substring(0, 2).toInt(16).toByte() }
    }

    private fun BigInteger.unsignedBytes(): ByteArray {
        val bytes = toByteArray()
        return if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}

internal class PeerChangedException : CertificateException("TV public key has changed")
