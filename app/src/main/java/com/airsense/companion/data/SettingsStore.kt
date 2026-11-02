package com.airsense.companion.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never leave app-private storage; backup is disabled in the manifest. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
    private val alias = "airsense_device_settings_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(settings: DeviceSettings) {
        val json = JSONObject().apply {
            put("host", settings.host); put("sshPort", settings.sshPort); put("username", settings.username)
            put("password", settings.password); put("privateKey", settings.privateKey); put("keyPassphrase", settings.keyPassphrase)
            put("logDirectory", settings.logDirectory); put("mqttPort", settings.mqttPort); put("mqttTopic", settings.mqttTopic)
            put("mqttUsername", settings.mqttUsername); put("mqttPassword", settings.mqttPassword)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("settings", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Could not save settings." }
    }

    fun load(): DeviceSettings {
        val value = prefs.getString("settings", null) ?: return DeviceSettings()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        }
        val json = JSONObject(String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8))
        return DeviceSettings(json.getString("host"), json.getInt("sshPort"), json.getString("username"),
            json.optString("password"), json.optString("privateKey"), json.optString("keyPassphrase"),
            json.getString("logDirectory"), json.getInt("mqttPort"), json.getString("mqttTopic"),
            json.optString("mqttUsername"), json.optString("mqttPassword"), configured = true)
    }

    fun fingerprint(host: String, port: Int): String? = prefs.getString("hostkey:$host:$port", null)
    fun trust(challenge: HostChallenge) {
        check(prefs.edit().putString("hostkey:${challenge.host}:${challenge.port}", challenge.fingerprint).commit())
    }
    fun clearCredentials() { check(prefs.edit().clear().commit()) }
}
