package com.example.xiaomiremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
internal class BluetoothWakeControls(
    private val activity: AppCompatActivity,
    private val store: TvStore,
    private val changed: () -> Unit
) {
    private var controller: BluetoothWakeDevice? = null
    private var work: Job? = null
    private var warmup: Job? = null
    private var pendingPermission: (() -> Unit)? = null
    private var pendingEnable: (() -> Unit)? = null
    private var pairingUntil = 0L

    private val permissionRequest = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingPermission
        pendingPermission = null
        if (result.values.isNotEmpty() && result.values.all { it }) action?.invoke()
        else toast(R.string.bt_permission_denied)
    }

    private val enableRequest = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingEnable
        pendingEnable = null
        if (hasConnectPermission() && adapter()?.isEnabled == true) action?.invoke()
        else toast(R.string.bt_disabled)
    }

    private val discoverableRequest = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode > 0) {
            pairingUntil = SystemClock.elapsedRealtime() + result.resultCode * 1_000L
            if (supported) prepare(null)
        } else {
            pairingUntil = 0
            toast(R.string.bt_not_discoverable)
        }
    }

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 28 && adapter() != null

    fun hasAssignment(tv: String): Boolean = store.bluetoothAddress(tv) != null

    fun showSetup(tv: String) {
        if (!supported) {
            toast(R.string.bt_not_supported)
            return
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.bt_title, tv))
            .setMessage(R.string.bt_setup_instructions)
            .setPositiveButton(R.string.bt_make_discoverable) { _, _ ->
                useBluetooth(advertise = true) { adapter -> makeDiscoverable(adapter) }
            }
            .setNegativeButton(R.string.bt_assign) { _, _ ->
                useBluetooth { adapter -> chooseHost(tv, adapter) }
            }
        if (hasAssignment(tv)) {
            dialog.setNeutralButton(R.string.bt_remove) { _, _ ->
                removeAssignment(tv)
            }
        }
        dialog.show()
    }

    fun removeAssignment(tv: String) {
        if (!hasAssignment(tv)) return
        store.setBluetoothAddress(tv, null)
        stop()
        changed()
    }

    fun prepare(tv: String?) {
        if (Build.VERSION.SDK_INT < 28 || !hasConnectPermission() ||
            work?.isActive == true || warmup?.isActive == true
        ) return
        val adapter = adapter()?.takeIf { it.isEnabled } ?: return
        val address = tv?.let(store::bluetoothAddress)
        val host = adapter.bondedDevices.firstOrNull { it.address == address }
        if (host == null && SystemClock.elapsedRealtime() >= pairingUntil) return
        warmup = activity.lifecycleScope.launch {
            val hid = device(adapter)
            // Keep the paired HID link available while this TV is in use.
            if (host == null) tryBluetooth { hid.register() }
            else tryBluetooth { hid.connectTo(host) }
        }
    }

    fun wake(tv: String) {
        val address = store.bluetoothAddress(tv) ?: return
        useBluetooth { adapter ->
            val host = adapter.bondedDevices.firstOrNull { it.address == address }
            if (host == null) {
                toast(R.string.bt_bond_missing)
                return@useBluetooth
            }
            startWork {
                toast(R.string.bt_waking)
                val hid = device(adapter)
                if (!tryBluetooth { hid.register() }) {
                    toast(R.string.bt_profile_unavailable)
                } else if (!tryBluetooth { hid.connectTo(host) }) {
                    toast(R.string.bt_wake_unreachable)
                } else {
                    toast(if (runCatching { hid.sendWake(host) }.getOrDefault(false)) {
                        R.string.bt_wake_sent
                    } else {
                        R.string.bt_wake_failed
                    })
                }
            }
        }
    }

    fun stop() {
        work?.cancel()
        warmup?.cancel()
        work = null
        warmup = null
        if (Build.VERSION.SDK_INT >= 28) controller?.stop()
        controller = null
    }

    @RequiresApi(28)
    private fun makeDiscoverable(adapter: BluetoothAdapter) {
        startWork {
            if (!tryBluetooth { device(adapter).register() }) {
                toast(R.string.bt_profile_unavailable)
                return@startWork
            }
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            try {
                discoverableRequest.launch(intent)
            } catch (_: ActivityNotFoundException) {
                toast(R.string.bt_not_discoverable)
            }
        }
    }

    @RequiresApi(28)
    private fun chooseHost(tv: String, adapter: BluetoothAdapter) {
        val hosts = adapter.bondedDevices.sortedBy { it.name.orEmpty().lowercase() }
        if (hosts.isEmpty()) {
            toast(R.string.bt_no_bonds)
            return
        }
        val labels = hosts.map { host ->
            val name = host.name ?: activity.getString(R.string.bt_unknown_device)
            "$name · ${host.address.takeLast(8)}"
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.bt_select_host)
            .setItems(labels.toTypedArray()) { _, index -> assign(tv, adapter, hosts[index]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @RequiresApi(28)
    private fun assign(tv: String, adapter: BluetoothAdapter, host: BluetoothDevice) {
        startWork {
            toast(R.string.bt_checking_hid)
            val hid = device(adapter)
            if (!tryBluetooth { hid.register() }) {
                toast(R.string.bt_profile_unavailable)
            } else if (tryBluetooth { hid.connectTo(host) }) {
                store.setBluetoothAddress(tv, host.address)
                pairingUntil = 0
                toast(R.string.bt_assigned)
                changed()
            } else {
                toast(R.string.bt_setup_unreachable)
            }
        }
    }

    @RequiresApi(28)
    private fun device(adapter: BluetoothAdapter): BluetoothWakeDevice =
        controller ?: BluetoothWakeDevice(activity, adapter).also { controller = it }

    private fun startWork(action: suspend () -> Unit) {
        if (work?.isActive == true) return
        warmup?.cancel()
        work = activity.lifecycleScope.launch { action() }
    }

    private fun useBluetooth(advertise: Boolean = false, action: (BluetoothAdapter) -> Unit) {
        if (Build.VERSION.SDK_INT < 28) {
            toast(R.string.bt_not_supported)
            return
        }
        val adapter = adapter()
        if (adapter == null) {
            toast(R.string.bt_not_supported)
            return
        }
        if (pendingPermission != null || pendingEnable != null || work?.isActive == true) return
        val whenAllowed = {
            if (adapter.isEnabled) {
                action(adapter)
            } else {
                pendingEnable = { action(adapter) }
                try {
                    enableRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: ActivityNotFoundException) {
                    pendingEnable = null
                    toast(R.string.bt_disabled)
                }
            }
        }
        val missing = if (Build.VERSION.SDK_INT >= 31) {
            buildList {
                if (!hasConnectPermission()) add(Manifest.permission.BLUETOOTH_CONNECT)
                if (advertise && !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                }
            }
        } else {
            emptyList()
        }
        if (missing.isEmpty()) {
            whenAllowed()
        } else {
            pendingPermission = whenAllowed
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun adapter(): BluetoothAdapter? =
        activity.getSystemService(BluetoothManager::class.java)?.adapter

    private suspend fun tryBluetooth(action: suspend () -> Boolean): Boolean = try {
        action()
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
