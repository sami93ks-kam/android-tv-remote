package com.example.xiaomiremote

import android.content.Context
import android.util.Base64
import com.example.xiaomiremote.protocol.ClientIdentity
import org.json.JSONObject
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

internal data class PairedTv(val identity: ClientIdentity, val fingerprint: ByteArray)

internal class TvStore(context: Context) {
    private val prefs = context.getSharedPreferences("android_tv_v2_pairs", Context.MODE_PRIVATE)
    private val bluetooth = context.getSharedPreferences("tv_bluetooth_hosts", Context.MODE_PRIVATE)

    fun names(): Set<String> = prefs.all.keys

    fun read(name: String): PairedTv? {
        val raw = prefs.getString(name, null) ?: return null
        return runCatching {
            val record = JSONObject(raw)
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(record.decode("certificate").inputStream()) as X509Certificate
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(record.decode("privateKey")))
            val fingerprint = record.decode("tvKey")
            require(fingerprint.size == 32)
            PairedTv(ClientIdentity(certificate, key), fingerprint)
        }.getOrNull()
    }

    fun save(name: String, paired: PairedTv) {
        val record = JSONObject()
            .put("certificate", Base64.encodeToString(paired.identity.certificate.encoded, Base64.NO_WRAP))
            .put("privateKey", Base64.encodeToString(paired.identity.privateKey.encoded, Base64.NO_WRAP))
            .put("tvKey", Base64.encodeToString(paired.fingerprint, Base64.NO_WRAP))
        prefs.edit().putString(name, record.toString()).apply()
    }

    fun forget(name: String) {
        prefs.edit().remove(name).apply()
        setBluetoothAddress(name, null)
    }

    fun bluetoothAddress(name: String): String? = bluetooth.getString(name, null)

    fun setBluetoothAddress(name: String, address: String?) {
        bluetooth.edit().putString(name, address).apply()
    }

    private fun JSONObject.decode(field: String): ByteArray = Base64.decode(getString(field), Base64.DEFAULT)
}
