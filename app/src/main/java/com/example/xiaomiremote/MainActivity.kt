package com.example.xiaomiremote

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.xiaomiremote.protocol.CryptoUtils
import com.example.xiaomiremote.protocol.KeysUnavailable
import com.example.xiaomiremote.protocol.PairingCancelled
import com.example.xiaomiremote.protocol.PairingClient
import com.example.xiaomiremote.protocol.PairingCodeExpired
import com.example.xiaomiremote.protocol.PairingCodeRejected
import com.example.xiaomiremote.protocol.PeerChangedException
import com.example.xiaomiremote.protocol.RemoteConnection
import com.example.xiaomiremote.protocol.RemoteShortcut
import com.example.xiaomiremote.protocol.TlsLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLException

class MainActivity : AppCompatActivity() {
    private lateinit var discovery: TvDiscovery
    private lateinit var pairs: TvStore
    private lateinit var bluetoothWake: BluetoothWakeControls
    private var tvs = emptyList<DiscoveredTv>()
    private var discoveryFailed = false
    private var searching = false
    private var searchTimer: Job? = null
    private var message: CharSequence? = null
    private var session: Session? = null
    private var visible = false

    private val backToTvs = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = disconnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pairs = TvStore(this)
        bluetoothWake = BluetoothWakeControls(this, pairs, ::render)
        discovery = TvDiscovery(this) { devices, failed ->
            tvs = devices
            discoveryFailed = failed
            render()
        }

        onBackPressedDispatcher.addCallback(this, backToTvs)
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            message = null
            refresh()
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { disconnect() }
        findViewById<View>(R.id.btnRemoteBluetooth).setOnClickListener {
            session?.takeIf { it.ready }?.let { bluetoothWake.showSetup(it.name) }
        }
        bindRemoteButtons()
        render()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        refresh()
        bluetoothWake.prepare(session?.name)
    }

    override fun onStop() {
        visible = false
        searchTimer?.cancel()
        bluetoothWake.stop()
        discovery.stop()
        tvs = emptyList()
        super.onStop()
    }

    override fun onDestroy() {
        session?.link?.close()
        super.onDestroy()
    }

    private fun refresh() {
        if (!visible) return
        searching = true
        searchTimer?.cancel()
        discovery.start()
        searchTimer = lifecycleScope.launch {
            delay(6_000)
            searching = false
            render()
        }
    }

    private fun render() {
        val active = session
        val connected = active?.ready == true
        backToTvs.isEnabled = active != null
        findViewById<View>(R.id.tvPanel).isVisible = !connected
        findViewById<View>(R.id.remotePanel).isVisible = connected
        if (connected && active != null) {
            findViewById<TextView>(R.id.remoteTvName).text = active.name
            val canWake = bluetoothWake.supported && bluetoothWake.hasAssignment(active.name)
            findViewById<View>(R.id.btnPower).isEnabled =
                (canWake && active.powerOn == false) || active.features and RemoteConnection.FEATURE_POWER != 0
            findViewById<View>(R.id.btnRemoteBluetooth).isVisible = bluetoothWake.supported
            val volumeAvailable = active.features and RemoteConnection.FEATURE_VOLUME != 0
            for (id in listOf(R.id.btnVolDown, R.id.btnMute, R.id.btnVolUp)) {
                findViewById<View>(id).isEnabled = volumeAvailable
            }
            findViewById<View>(R.id.volumeUnavailable).isVisible = !volumeAvailable
            return
        }

        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = message ?: when {
            discoveryFailed -> getString(R.string.discovery_failed)
            tvs.isNotEmpty() -> getString(R.string.discovery_pick)
            searching -> getString(R.string.discovery_searching)
            else -> getString(R.string.discovery_empty)
        }
        findViewById<Button>(R.id.btnRefresh).isEnabled = active == null
        val rows = findViewById<LinearLayout>(R.id.tvRows)
        rows.removeAllViews()
        val saved = pairs.names()
        val online = tvs.associateBy { it.name }
        for (name in online.keys + (saved - online.keys).sorted()) {
            val tv = online[name]
            val paired = name in saved
            val row = layoutInflater.inflate(R.layout.row_tv, rows, false)
            bindTvRow(row, name, paired, tv, busy = active != null)
            rows.addView(row)
        }
    }

    private fun bindTvRow(row: View, name: String, paired: Boolean, tv: DiscoveredTv?, busy: Boolean) {
        row.findViewById<TextView>(R.id.deviceName).text = name
        val detail = row.findViewById<TextView>(R.id.deviceDetail)
        detail.isVisible = paired
        detail.setText(if (tv == null) R.string.device_saved_offline else R.string.device_saved)
        row.findViewById<Button>(R.id.btnConnect).apply {
            isEnabled = tv != null && !busy
            setOnClickListener { if (tv != null) connect(tv) }
        }
        row.findViewById<Button>(R.id.btnForget).apply {
            isVisible = paired
            isEnabled = !busy
            setOnClickListener { confirmForget(name) }
        }
        val bluetoothAvailable = paired && bluetoothWake.supported
        row.findViewById<View>(R.id.btActions).isVisible = bluetoothAvailable
        row.findViewById<View>(R.id.btnBluetoothSetup).apply {
            isEnabled = !busy
            setOnClickListener { bluetoothWake.showSetup(name) }
        }
        row.findViewById<View>(R.id.btnBluetoothWake).apply {
            isVisible = bluetoothAvailable && bluetoothWake.hasAssignment(name)
            isEnabled = !busy
            setOnClickListener { bluetoothWake.wake(name) }
        }
    }

    private fun confirmForget(name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.forget_title)
            .setMessage(getString(R.string.forget_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.forget) { _, _ ->
                bluetoothWake.removeAssignment(name)
                pairs.forget(name)
                message = getString(R.string.forgotten)
                render()
            }
            .show()
    }

    private fun connect(tv: DiscoveredTv) {
        if (session != null) return
        val attempt = Session(tv.name)
        session = attempt
        message = getString(R.string.connecting_to, tv.name)
        render()

        attempt.job = lifecycleScope.launch {
            var paired: PairedTv? = null
            var rediscover = false
            try {
                paired = withContext(Dispatchers.IO) { pairs.read(tv.name) }
                if (paired == null) {
                    message = getString(R.string.starting_pairing)
                    render()
                    val identity = withContext(Dispatchers.IO) { CryptoUtils.createIdentity() }
                    val fingerprint = PairingClient(attempt.link).pair(tv.addresses, identity) { incorrect ->
                        promptForPin(tv.name, incorrect)
                    }
                    paired = PairedTv(identity, fingerprint)
                    pairs.save(tv.name, paired)
                    attempt.link.close()
                    attempt.link = TlsLink()
                    message = getString(R.string.connecting_to, tv.name)
                    render()
                }

                val endpoint = tvs.firstOrNull { it.name == tv.name } ?: tv
                val remote = RemoteConnection(attempt.link)
                attempt.remote = remote
                remote.run(
                    endpoint.addresses,
                    endpoint.port,
                    paired.identity,
                    paired.fingerprint,
                    onShortcutRejected = { shortcut ->
                        withContext(Dispatchers.Main) {
                            if (visible && session === attempt) showShortcutError(shortcut)
                        }
                    },
                    onPowerChanged = { powerOn ->
                        withContext(Dispatchers.Main) {
                            if (session === attempt) {
                                attempt.powerOn = powerOn
                                if (attempt.ready) render()
                            }
                        }
                    }
                ) { features ->
                    withContext(Dispatchers.Main) {
                        if (session === attempt) {
                            attempt.features = features
                            attempt.ready = true
                            message = null
                            render()
                            if (visible) bluetoothWake.prepare(attempt.name)
                            findViewById<View>(R.id.mainScroll).scrollTo(0, 0)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (session === attempt) {
                    message = getString(connectionError(error, attempt.ready, paired != null))
                    rediscover = attempt.ready || (paired != null && error !is KeysUnavailable)
                }
            } finally {
                attempt.link.close()
                if (session === attempt) {
                    session = null
                    if (rediscover) refresh() else render()
                }
            }
        }
    }

    private fun connectionError(error: Exception, connected: Boolean, saved: Boolean): Int = when {
        connected -> R.string.connection_lost
        error is PairingCancelled -> R.string.pair_cancelled
        error is PairingCodeExpired -> R.string.pin_expired
        error is PairingCodeRejected -> R.string.pin_rejected
        error is KeysUnavailable -> R.string.remote_keys_unavailable
        generateSequence<Throwable>(error) { it.cause }.any { it is PeerChangedException } -> R.string.tv_identity_changed
        saved && error is SSLException -> R.string.tv_pairing_rejected
        saved -> R.string.reconnect_failed
        else -> R.string.pair_failed
    }

    private fun disconnect() {
        val active = session ?: return
        session = null
        active.link.close()
        active.job?.cancel()
        message = null
        refresh()
        render()
    }

    private suspend fun promptForPin(tvName: String, incorrect: Boolean): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        val input = EditText(this@MainActivity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
            hint = getString(R.string.pin_hint)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 24f
        }
        val dialog = AlertDialog.Builder(this@MainActivity)
            .setTitle(R.string.pin_title)
            .setMessage(getString(R.string.pin_message, tvName))
            .setView(FrameLayout(this@MainActivity).apply {
                setPadding(24.dp, 0, 24.dp, 0)
                addView(input)
            })
            .setPositiveButton(R.string.pin_submit, null)
            .setNegativeButton(R.string.cancel) { _, _ -> result.complete(null) }
            .setOnCancelListener { result.complete(null) }
            .create()

        try {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.show()
            input.requestFocus()
            if (incorrect) input.error = getString(R.string.pin_incorrect)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.matches(CryptoUtils.pinPattern)) result.complete(pin)
                else input.error = getString(R.string.pin_format)
            }
            result.await()
        } finally {
            dialog.dismiss()
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun bindRemoteButtons() {
        findViewById<View>(R.id.btnPower).setOnClickListener {
            val active = session?.takeIf { it.ready } ?: return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (active.powerOn == false && bluetoothWake.hasAssignment(active.name)) {
                bluetoothWake.wake(active.name)
            } else {
                active.remote?.sendKey(RemoteConnection.KEY_POWER)
            }
        }
        val commands = mapOf(
            R.id.btnUp to RemoteConnection.KEY_UP,
            R.id.btnDown to RemoteConnection.KEY_DOWN,
            R.id.btnLeft to RemoteConnection.KEY_LEFT,
            R.id.btnRight to RemoteConnection.KEY_RIGHT,
            R.id.btnOk to RemoteConnection.KEY_OK,
            R.id.btnBack to RemoteConnection.KEY_BACK,
            R.id.btnHome to RemoteConnection.KEY_HOME,
            R.id.btnMenu to RemoteConnection.KEY_MENU,
            R.id.btnVolUp to RemoteConnection.KEY_VOL_UP,
            R.id.btnVolDown to RemoteConnection.KEY_VOL_DOWN,
            R.id.btnMute to RemoteConnection.KEY_MUTE
        )
        for ((button, key) in commands) {
            findViewById<View>(button).setOnClickListener {
                val active = session
                if (active?.ready == true) {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    active.remote?.sendKey(key)
                }
            }
        }

        val shortcuts = mapOf(
            R.id.btnSources to RemoteShortcut.INPUT,
            R.id.btnYouTube to RemoteShortcut.YOUTUBE,
            R.id.btnNetflix to RemoteShortcut.NETFLIX
        )
        for ((button, shortcut) in shortcuts) {
            findViewById<View>(button).setOnClickListener {
                val active = session ?: return@setOnClickListener
                if (!active.ready) return@setOnClickListener
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (shortcut.appLink != null && active.features and RemoteConnection.FEATURE_APP_LINK == 0) {
                    Toast.makeText(this, R.string.app_links_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    active.remote?.sendShortcut(shortcut)
                }
            }
        }
    }

    private fun showShortcutError(shortcut: RemoteShortcut) {
        val text = when (shortcut) {
            RemoteShortcut.INPUT -> getString(R.string.sources_rejected)
            RemoteShortcut.YOUTUBE -> getString(R.string.app_launch_rejected, getString(R.string.youtube))
            RemoteShortcut.NETFLIX -> getString(R.string.app_launch_rejected, getString(R.string.netflix))
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private class Session(val name: String) {
        var link = TlsLink()
        var job: Job? = null
        var remote: RemoteConnection? = null
        var ready = false
        var features = 0
        var powerOn: Boolean? = null
    }
}
