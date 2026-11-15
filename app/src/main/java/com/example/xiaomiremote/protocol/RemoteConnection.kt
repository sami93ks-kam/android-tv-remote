package com.example.xiaomiremote.protocol

import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException

internal class RemoteConnection(private val link: TlsLink) {
    private val commands = Channel<ProtoWriter>(32)

    suspend fun run(
        addresses: List<InetAddress>,
        port: Int,
        identity: ClientIdentity,
        fingerprint: ByteArray,
        onShortcutRejected: suspend (RemoteShortcut) -> Unit,
        onPowerChanged: suspend (Boolean) -> Unit,
        onReady: suspend (features: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tls = CryptoUtils.createSSLContext(identity, fingerprint)
        link.connect(tls, addresses, port).use { socket ->
            coroutineScope {
                val input = socket.inputStream
                val output = socket.outputStream
                val writeLock = Any()
                fun send(message: ProtoWriter) = synchronized(writeLock) { message.writeDelimitedTo(output) }

                var features = 0
                var ready = false
                var writer: Job? = null
                val handshakeStarted = System.nanoTime()

                try {
                    while (true) {
                        if (!ready && System.nanoTime() - handshakeStarted > 10_000_000_000L) {
                            throw SocketTimeoutException("TV did not start the remote session")
                        }

                        var rejectedShortcut: RemoteShortcut? = null
                        var power: Boolean? = null
                        ProtoReader.readDelimited(input).forEachField { field, wire ->
                            if (wire != 2) {
                                skipField(wire)
                            } else {
                                val message = readBytes()
                                when (field) {
                                    1 -> {
                                        features = readFirstInt(message) and CLIENT_FEATURES
                                        if (features and FEATURE_KEYS == 0) throw KeysUnavailable()
                                        send(configuration(features))
                                    }
                                    2 -> send(ProtoWriter().writeMessage(2, ProtoWriter().writeInt32(1, features)))
                                    3 -> rejectedShortcut = readRejectedShortcut(message)
                                    8 -> send(ProtoWriter().writeMessage(9, ProtoWriter().writeInt32(1, readFirstInt(message))))
                                    40 -> {
                                        power = readFirstInt(message) != 0
                                        if (!ready) {
                                            if (features and FEATURE_KEYS == 0) throw KeysUnavailable()
                                            ready = true
                                            socket.soTimeout = 25_000
                                        }
                                    }
                                }
                            }
                        }
                        rejectedShortcut?.let { onShortcutRejected(it) }
                        power?.let { onPowerChanged(it) }
                        if (ready && writer == null) {
                            writer = launch {
                                try {
                                    for (command in commands) send(command)
                                } catch (error: IOException) {
                                    link.close()
                                    throw error
                                }
                            }
                            onReady(features)
                        }
                    }
                } finally {
                    link.close()
                    writer?.cancel()
                    commands.cancel()
                }
            }
        }
    }

    fun sendKey(keyCode: Int) {
        val key = ProtoWriter().writeInt32(1, keyCode).writeInt32(2, 3)
        commands.trySend(ProtoWriter().writeMessage(KEY_INJECT_FIELD, key))
    }

    fun sendShortcut(shortcut: RemoteShortcut) {
        val link = shortcut.appLink
        if (link == null) {
            sendKey(KeyEvent.KEYCODE_TV_INPUT)
        } else {
            val request = ProtoWriter().writeString(1, link)
            commands.trySend(ProtoWriter().writeMessage(APP_LINK_FIELD, request))
        }
    }

    private fun readRejectedShortcut(error: ByteArray): RemoteShortcut? {
        // RemoteError may echo the rejected request. It does not carry a reason code.
        val rejected = readMessage(error, 2) ?: return null
        val key = readMessage(rejected, KEY_INJECT_FIELD)
        if (key != null && readFirstInt(key) == KeyEvent.KEYCODE_TV_INPUT) {
            return RemoteShortcut.INPUT
        }
        val request = readMessage(rejected, APP_LINK_FIELD) ?: return null
        val link = readMessage(request, 1)?.toString(Charsets.UTF_8) ?: return null
        return RemoteShortcut.entries.firstOrNull { it.appLink == link }
    }

    private fun readMessage(message: ByteArray, number: Int): ByteArray? {
        var value: ByteArray? = null
        ProtoReader(message).forEachField { field, wire ->
            if (field == number && wire == 2) value = readBytes() else skipField(wire)
        }
        return value
    }

    private fun configuration(features: Int): ProtoWriter {
        val client = ProtoWriter().writeInt32(3, 1).writeString(4, "1")
            .writeString(5, "atvremote").writeString(6, "2.2")
        val configure = ProtoWriter().writeInt32(1, features).writeMessage(2, client)
        return ProtoWriter().writeMessage(1, configure)
    }

    private fun readFirstInt(message: ByteArray): Int {
        var value = 0
        ProtoReader(message).forEachField { field, wire ->
            if (field == 1 && wire == 0) value = readInt32() else skipField(wire)
        }
        return value
    }

    companion object {
        private const val FEATURE_PING = 1
        private const val FEATURE_KEYS = 2
        const val FEATURE_POWER = 32
        const val FEATURE_VOLUME = 64
        const val FEATURE_APP_LINK = 1 shl 9
        private const val CLIENT_FEATURES =
            FEATURE_PING or FEATURE_KEYS or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK
        private const val KEY_INJECT_FIELD = 10
        private const val APP_LINK_FIELD = 90

        const val KEY_POWER = KeyEvent.KEYCODE_POWER
        const val KEY_UP = KeyEvent.KEYCODE_DPAD_UP
        const val KEY_DOWN = KeyEvent.KEYCODE_DPAD_DOWN
        const val KEY_LEFT = KeyEvent.KEYCODE_DPAD_LEFT
        const val KEY_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
        const val KEY_OK = KeyEvent.KEYCODE_DPAD_CENTER
        const val KEY_BACK = KeyEvent.KEYCODE_BACK
        const val KEY_HOME = KeyEvent.KEYCODE_HOME
        const val KEY_MENU = KeyEvent.KEYCODE_MENU
        const val KEY_VOL_UP = KeyEvent.KEYCODE_VOLUME_UP
        const val KEY_VOL_DOWN = KeyEvent.KEYCODE_VOLUME_DOWN
        const val KEY_MUTE = KeyEvent.KEYCODE_VOLUME_MUTE
    }
}

internal enum class RemoteShortcut(val appLink: String? = null) {
    INPUT,
    YOUTUBE("vnd.youtube.launch://"),
    NETFLIX("netflix://")
}

internal class KeysUnavailable : IOException()
