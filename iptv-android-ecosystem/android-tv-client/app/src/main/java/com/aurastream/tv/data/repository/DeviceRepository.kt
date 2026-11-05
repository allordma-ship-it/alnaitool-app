package com.aurastream.tv.data.repository

import android.content.Context
import android.os.Build
import com.aurastream.tv.BuildConfig
import com.aurastream.tv.core.DeviceIdentity
import com.aurastream.tv.data.local.DeviceCredentialStore
import com.aurastream.tv.data.remote.AuraApiService
import com.aurastream.tv.data.remote.dto.ApiError
import com.aurastream.tv.data.remote.dto.DeviceResponse
import com.aurastream.tv.data.remote.dto.HandshakeRequest
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

enum class LicenseStatus { TRIAL, ACTIVE, EXPIRED, UNKNOWN;

    companion object {
        fun from(value: String?) = when (value?.lowercase()) {
            "trial" -> TRIAL
            "active" -> ACTIVE
            "expired" -> EXPIRED
            else -> UNKNOWN
        }
    }
}

data class DeviceSession(
    val mac: String,
    val deviceKey: String,
    val status: LicenseStatus,
    val daysLeft: Int?,
    val trialEndsAt: String?,
    val portalUrl: String,
    val pairingUrl: String,
)

/** Non-2xx backend response. */
class ApiException(val httpCode: Int, val error: String?, message: String?) :
    IOException(message ?: "HTTP $httpCode")

/** The MAC is registered but this installation lost its device secret (reinstall / data wipe). */
class DeviceConflictException(message: String?) : IOException(message)

class DeviceRepository(
    private val context: Context,
    private val api: AuraApiService,
    private val credentials: DeviceCredentialStore,
    private val gson: Gson,
) {
    private val _session = MutableStateFlow<DeviceSession?>(null)
    val session: StateFlow<DeviceSession?> = _session.asStateFlow()

    /** Stable MAC-formatted identity, resolved once and persisted. */
    fun macAddress(): String =
        credentials.macAddress ?: DeviceIdentity.resolve(context).also { credentials.macAddress = it }

    /** Last known pairing data for offline display. */
    fun cachedDeviceKey(): String? = credentials.deviceKey

    /**
     * Registers / refreshes this device with the backend.
     * @param reset re-pair a MAC whose secret was lost. Rotates the key and purges remote playlists.
     */
    suspend fun handshake(reset: Boolean = false): DeviceSession = withContext(Dispatchers.IO) {
        val mac = macAddress()
        val response = api.handshake(
            HandshakeRequest(
                mac = mac,
                deviceSecret = credentials.deviceSecret,
                model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120),
                appVersion = BuildConfig.VERSION_NAME,
                reset = if (reset) true else null,
            ),
        )
        if (response.code() == 409) {
            throw DeviceConflictException(parseError(response)?.message)
        }
        val body = response.bodyOrThrow(gson)
        body.deviceSecret?.let { credentials.deviceSecret = it }
        credentials.deviceKey = body.deviceKey
        body.toSession().also { _session.value = it }
    }

    /** Updates licence information from a sync response. */
    fun updateLicense(status: LicenseStatus, daysLeft: Int?) {
        _session.value = _session.value?.copy(status = status, daysLeft = daysLeft)
    }

    private fun DeviceResponse.toSession() = DeviceSession(
        mac = mac,
        deviceKey = deviceKey,
        status = LicenseStatus.from(status),
        daysLeft = daysLeft,
        trialEndsAt = trialEndsAt,
        portalUrl = portalUrl,
        pairingUrl = pairingUrl,
    )

    private fun parseError(response: Response<*>): ApiError? =
        runCatching { gson.fromJson(response.errorBody()?.charStream(), ApiError::class.java) }.getOrNull()
}

/** Shared helper: returns the body of a successful response or throws [ApiException]. */
internal fun <T> Response<T>.bodyOrThrow(gson: Gson): T {
    if (isSuccessful) return body() ?: throw ApiException(code(), "EMPTY_BODY", "Empty response")
    val err = runCatching { gson.fromJson(errorBody()?.charStream(), ApiError::class.java) }.getOrNull()
    throw ApiException(code(), err?.error, err?.message)
}
