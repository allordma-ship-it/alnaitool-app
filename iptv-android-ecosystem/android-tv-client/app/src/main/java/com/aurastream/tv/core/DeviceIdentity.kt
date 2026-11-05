package com.aurastream.tv.core

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale

/**
 * Resolves a stable, MAC-formatted device identifier.
 *
 * 1. Real hardware address of `eth0` / `wlan0` – still readable on many Android TV
 *    boxes running Android 9 or below.
 * 2. Otherwise (Android 10+ returns `02:00:00:00:00:00` or nothing) a deterministic
 *    pseudo-MAC derived from `Settings.Secure.ANDROID_ID`. The locally-administered
 *    bit is set so it can never collide with a vendor-assigned MAC.
 *
 * The caller persists the first resolved value so the identity never flips between
 * Ethernet and Wi-Fi when the active interface changes.
 */
object DeviceIdentity {

    private val INVALID = setOf("02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")
    private val PREFERRED_INTERFACES = listOf("eth0", "wlan0", "eth1", "wlan1")

    fun resolve(context: Context): String = hardwareMac() ?: androidIdMac(context)

    private fun hardwareMac(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        PREFERRED_INTERFACES.firstNotNullOfOrNull { name ->
            interfaces.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.hardwareAddress
                ?.takeIf { it.size == 6 }
                ?.let(::format)
                ?.takeUnless { it in INVALID }
        }
    }.getOrNull()

    @SuppressLint("HardwareIds")
    private fun androidIdMac(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } // known buggy emulator/legacy value
            ?: (android.os.Build.FINGERPRINT + android.os.Build.MODEL)
        val digest = MessageDigest.getInstance("SHA-256").digest("aurastream:$androidId".toByteArray())
        val bytes = digest.copyOfRange(0, 6)
        // Locally administered (bit 1 set), unicast (bit 0 cleared).
        bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
        return format(bytes)
    }

    private fun format(bytes: ByteArray): String =
        bytes.joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

}
