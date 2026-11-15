package com.example.xiaomiremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@RequiresApi(28)
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before using this controller.
internal class BluetoothWakeDevice(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {
    private val executor = context.mainExecutor
    private var registration: Registration? = null

    suspend fun register(): Boolean {
        if (!adapter.isEnabled) return false
        val attempt = registration ?: Registration().also {
            registration = it
            if (!runCatching { adapter.getProfileProxy(context, it, BluetoothProfile.HID_DEVICE) }.getOrDefault(false)) {
                it.ready.complete(false)
            }
        }
        val ready = withTimeoutOrNull(5_000) { attempt.ready.await() } ?: false
        if (!ready && registration === attempt) stop()
        return ready && registration === attempt
    }

    suspend fun connectTo(host: BluetoothDevice): Boolean {
        if (host.bondState != BluetoothDevice.BOND_BONDED || !register()) return false
        val attempt = registration ?: return false
        val hid = attempt.profile ?: return false
        if (hid.getConnectionState(host) == BluetoothProfile.STATE_CONNECTED) return true
        for (other in hid.connectedDevices) {
            if (other != host) hid.disconnect(other)
        }

        val connecting = hid.getConnectionState(host) == BluetoothProfile.STATE_CONNECTING
        if (!connecting && !hid.connect(host)) return false
        return withTimeoutOrNull(7_000) {
            attempt.connected.first { it == host || registration !== attempt }
            registration === attempt && hid.getConnectionState(host) == BluetoothProfile.STATE_CONNECTED
        } == true
    }

    fun sendWake(host: BluetoothDevice): Boolean {
        val hid = registration?.profile ?: return false
        if (host.bondState != BluetoothDevice.BOND_BONDED ||
            hid.getConnectionState(host) != BluetoothProfile.STATE_CONNECTED
        ) return false
        if (!hid.sendReport(host, WAKE_REPORT_ID, byteArrayOf(1))) return false
        return hid.sendReport(host, WAKE_REPORT_ID, byteArrayOf(0))
    }

    fun stop() {
        val attempt = registration ?: return
        registration = null
        attempt.ready.complete(false)
        attempt.connected.value = null
        val hid = attempt.profile ?: return
        attempt.profile = null
        runCatching { hid.unregisterApp() }
        adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
    }

    private inner class Registration : BluetoothProfile.ServiceListener {
        val ready = CompletableDeferred<Boolean>()
        val connected = MutableStateFlow<BluetoothDevice?>(null)
        var profile: BluetoothHidDevice? = null

        override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
            if (profileId != BluetoothProfile.HID_DEVICE) return
            val hid = proxy as BluetoothHidDevice
            executor.execute {
                if (registration !== this) {
                    adapter.closeProfileProxy(profileId, hid)
                } else {
                    profile = hid
                    val info = BluetoothHidDeviceAppSdpSettings(
                        "Xiaomi TV Remote", "System Wake Up", "Kamil IT Solution",
                        BluetoothHidDevice.SUBCLASS2_REMOTE_CONTROL, wakeDescriptor
                    )
                    val started = runCatching { hid.registerApp(info, null, null, executor, callback) }
                        .getOrDefault(false)
                    if (!started) ready.complete(false)
                }
            }
        }

        override fun onServiceDisconnected(profileId: Int) {
            if (profileId == BluetoothProfile.HID_DEVICE) {
                executor.execute { if (registration === this) stop() }
            }
        }

        private val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (registration !== this@Registration) return
                if (registered) {
                    ready.complete(true)
                } else {
                    stop()
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                if (registration !== this@Registration) return
                if (state == BluetoothProfile.STATE_CONNECTED) connected.value = device
                else if (connected.value == device) connected.value = null
            }

            override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                val hid = profile?.takeIf { registration === this@Registration } ?: return
                if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == WAKE_REPORT_ID) {
                    hid.replyReport(device, type, id, byteArrayOf(0))
                } else {
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                }
            }
        }
    }

    companion object {
        private const val WAKE_REPORT_ID = 1

        // Generic Desktop / System Control, with a one-bit System Wake Up input.
        private val wakeDescriptor = byteArrayOf(
            0x05, 0x01, 0x09, 0x80.toByte(), 0xA1.toByte(), 0x01,
            0x85.toByte(), WAKE_REPORT_ID.toByte(), 0x09, 0x83.toByte(),
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x01, 0x81.toByte(), 0x02,
            0x75, 0x07, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03, 0xC0.toByte()
        )
    }
}
