package com.example.xiaomiremote.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate

internal class PairingClient(private val link: TlsLink) {
    suspend fun pair(
        addresses: List<InetAddress>,
        identity: ClientIdentity,
        readPin: suspend (incorrect: Boolean) -> String?
    ): ByteArray = withContext(Dispatchers.IO) {
        val tls = CryptoUtils.createSSLContext(identity, peerFingerprint = null)
        link.connect(tls, addresses, PAIRING_PORT).use { socket ->
            val serverCert = socket.session.peerCertificates[0] as X509Certificate
            val input = socket.inputStream
            val output = socket.outputStream

            // Android TV Remote v2 uses nested Polo messages inside a length-delimited envelope.
            val request = ProtoWriter().writeString(1, "atvremote").writeString(2, "Kamil IT Solution")
            send(output, 10, request)
            expect(input, 11)

            val encoding = ProtoWriter().writeInt32(1, 3).writeInt32(2, 6)
            send(output, 20, ProtoWriter().writeMessage(1, encoding).writeInt32(3, 1))
            expect(input, 20)
            send(output, 30, ProtoWriter().writeMessage(1, encoding).writeInt32(2, 1))
            expect(input, 31)

            val secret = withTimeoutOrNull(60_000) {
                var hash: ByteArray? = null
                var incorrect = false
                while (hash == null) {
                    val pin = readPin(incorrect) ?: throw PairingCancelled()
                    hash = CryptoUtils.computePairingSecret(identity.certificate, serverCert, pin)
                    incorrect = true
                }
                hash
            } ?: throw PairingCodeExpired()

            try {
                send(output, 40, ProtoWriter().writeBytes(1, secret))
                expect(input, 41)
            } catch (_: EOFException) {
                throw PairingCodeExpired()
            } catch (_: SocketTimeoutException) {
                throw PairingCodeExpired()
            } catch (_: SocketException) {
                throw PairingCodeExpired()
            }
            CryptoUtils.publicKeyFingerprint(serverCert)
        }
    }

    private fun send(out: OutputStream, field: Int, payload: ProtoWriter) {
        ProtoWriter().writeInt32(1, 2).writeInt32(2, 200)
            .writeMessage(field, payload).writeDelimitedTo(out)
    }

    private fun expect(input: InputStream, expectedField: Int) {
        var version = 0
        var status = 0
        var expected = false
        ProtoReader.readDelimited(input).forEachField { field, wire ->
            when {
                field == 1 && wire == 0 -> version = readInt32()
                field == 2 && wire == 0 -> status = readInt32()
                field == expectedField && wire == 2 -> {
                    readBytes()
                    expected = true
                }
                else -> skipField(wire)
            }
        }
        if (status == 402) throw PairingCodeRejected()
        if (version != 2 || status != 200 || !expected) throw IOException("Unexpected pairing reply")
    }

    companion object {
        private const val PAIRING_PORT = 6467
    }
}

internal class PairingCancelled : IOException()
internal class PairingCodeExpired : IOException()
internal class PairingCodeRejected : IOException()
