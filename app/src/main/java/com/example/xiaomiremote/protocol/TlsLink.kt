package com.example.xiaomiremote.protocol

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

internal class TlsLink : Closeable {
    private val lock = Any()
    private var raw: Socket? = null
    private var closed = false

    fun connect(context: SSLContext, addresses: List<InetAddress>, port: Int): SSLSocket {
        var failure: IOException? = null
        for (address in addresses) {
            val transport = synchronized(lock) {
                if (closed) throw SocketException("Connection closed")
                Socket().also { raw = it }
            }
            try {
                transport.connect(InetSocketAddress(address, port), 4_000)
                transport.tcpNoDelay = true
                val socket = context.socketFactory.createSocket(
                    transport, address.hostAddress, port, true
                ) as SSLSocket
                socket.soTimeout = 8_000
                socket.startHandshake()
                return socket
            } catch (error: IOException) {
                runCatching { transport.close() }
                // Preserve handshake errors when another address simply times out.
                if (failure == null || error is SSLException) failure = error
            }
        }
        throw failure ?: SocketException("No TV address")
    }

    override fun close() {
        val transport = synchronized(lock) {
            closed = true
            raw.also { raw = null }
        }
        runCatching { transport?.close() }
    }
}
