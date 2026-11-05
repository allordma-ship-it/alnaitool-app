package com.aurastream.tv.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the device identity (MAC) and the backend-issued device secret.
 *
 * The secret is encrypted with an AES-256-GCM key that never leaves the Android
 * Keystore. Some low-end TV boxes ship broken Keystore implementations; in that
 * case we degrade to app-private storage rather than locking the user out.
 */
class DeviceCredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("aura_device", Context.MODE_PRIVATE)

    var macAddress: String?
        get() = prefs.getString(KEY_MAC, null)
        set(value) = prefs.edit().putString(KEY_MAC, value).apply()

    var deviceKey: String?
        get() = prefs.getString(KEY_DEVICE_KEY, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_KEY, value).apply()

    var deviceSecret: String?
        get() = prefs.getString(KEY_SECRET, null)?.let(::decrypt)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_SECRET).apply()
            else prefs.edit().putString(KEY_SECRET, encrypt(value)).apply()
        }

    private fun keystoreKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generator.generateKey()
        }
    }.onFailure { Log.w(TAG, "Android Keystore unavailable, using private storage", it) }.getOrNull()

    private fun encrypt(plain: String): String {
        val key = keystoreKey() ?: return PLAIN_PREFIX + plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            ENC_PREFIX + b64(cipher.iv) + ":" + b64(ct)
        }.getOrElse { PLAIN_PREFIX + plain }
    }

    private fun decrypt(stored: String): String? = when {
        stored.startsWith(PLAIN_PREFIX) -> stored.removePrefix(PLAIN_PREFIX)
        stored.startsWith(ENC_PREFIX) -> runCatching {
            val (iv, ct) = stored.removePrefix(ENC_PREFIX).split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, unb64(iv)))
            String(cipher.doFinal(unb64(ct)), Charsets.UTF_8)
        }.onFailure { Log.e(TAG, "Unable to decrypt device secret", it) }.getOrNull()
        else -> null
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(value: String) = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val TAG = "DeviceCredentialStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aura_device_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENC_PREFIX = "k1:"
        const val PLAIN_PREFIX = "p1:"
        const val KEY_MAC = "mac"
        const val KEY_DEVICE_KEY = "device_key"
        const val KEY_SECRET = "device_secret"
    }
}
