package com.example.xiaomiremote

import android.annotation.TargetApi
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor

internal data class DiscoveredTv(val name: String, val addresses: List<InetAddress>, val port: Int)

@Suppress("DEPRECATION")
internal class TvDiscovery(
    context: Context,
    private val changed: (List<DiscoveredTv>, failed: Boolean) -> Unit
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }
    private val found = mutableMapOf<String, NsdServiceInfo>()
    private val resolved = mutableMapOf<String, DiscoveredTv>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val stopObservers = mutableMapOf<String, () -> Unit>()
    private var multicast: WifiManager.MulticastLock? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolving = false

    fun start() {
        stop()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                main.post {
                    if (discovery != this || service.serviceName in found) return@post
                    found[service.serviceName] = service
                    if (Build.VERSION.SDK_INT >= 34) observe(service) else enqueue(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                main.post {
                    if (discovery != this) return@post
                    val name = service.serviceName
                    found.remove(name)
                    stopObservers.remove(name)?.invoke()
                    if (resolved.remove(name) != null) report()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = failed(this)

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit
        }

        discovery = listener
        report()
        try {
            multicast = wifi.createMulticastLock("XiaomiRemote: discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            failed(listener)
        }
    }

    fun stop() {
        val old = discovery
        discovery = null
        old?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        stopObservers.values.forEach { it() }
        stopObservers.clear()
        found.clear()
        resolved.clear()
        pending.clear()
        multicast?.let { if (it.isHeld) it.release() }
        multicast = null
    }

    private fun failed(listener: NsdManager.DiscoveryListener) {
        main.post {
            if (discovery != listener) return@post
            stop()
            changed(emptyList(), true)
        }
    }

    @TargetApi(34)
    private fun observe(service: NsdServiceInfo) {
        val name = service.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                if (found[name] !== service) return
                stopObservers.remove(name)
                enqueue(service)
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (found[name] === service) publish(name, serviceInfo.hostAddresses, serviceInfo.port)
            }

            override fun onServiceLost() {
                if (found[name] === service && resolved.remove(name) != null) report()
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        try {
            nsd.registerServiceInfoCallback(service, executor, callback)
            stopObservers[name] = { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
        } catch (_: RuntimeException) {
            enqueue(service)
        }
    }

    private fun enqueue(service: NsdServiceInfo) {
        pending.addLast(service)
        resolveNext()
    }

    private fun resolveNext() {
        if (resolving) return
        while (pending.isNotEmpty()) {
            val service = pending.removeFirst()
            if (found[service.serviceName] !== service) continue
            resolving = true
            resolve(service, canRetry = true)
            return
        }
    }

    // Older NsdManager versions accept only one concurrent resolve.
    private fun resolve(service: NsdServiceInfo, canRetry: Boolean) {
        val name = service.serviceName
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    if (found[name] === service) publish(name, listOfNotNull(info.host), info.port)
                    resolving = false
                    resolveNext()
                }
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post {
                    if (canRetry && found[name] === service) {
                        main.postDelayed({
                            if (found[name] === service) resolve(service, canRetry = false)
                            else {
                                resolving = false
                                resolveNext()
                            }
                        }, 400)
                    } else {
                        resolving = false
                        resolveNext()
                    }
                }
            }
        }
        try {
            nsd.resolveService(service, listener)
        } catch (_: RuntimeException) {
            listener.onResolveFailed(service, NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }

    private fun publish(name: String, addresses: List<InetAddress>, port: Int) {
        val usable = addresses.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress }
            .distinct().sortedBy { it !is Inet4Address }
        if (port !in 1..65535 || usable.isEmpty()) {
            if (resolved.remove(name) != null) report()
            return
        }
        val tv = DiscoveredTv(name, usable, port)
        if (resolved.put(name, tv) != tv) report()
    }

    private fun report() {
        changed(resolved.values.sortedBy { it.name.lowercase() }, false)
    }

    companion object {
        private const val SERVICE_TYPE = "_androidtvremote2._tcp."
    }
}
