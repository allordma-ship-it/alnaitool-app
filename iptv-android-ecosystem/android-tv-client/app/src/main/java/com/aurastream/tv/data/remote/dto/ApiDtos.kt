package com.aurastream.tv.data.remote.dto

import com.google.gson.annotations.SerializedName

data class HandshakeRequest(
    @SerializedName("mac") val mac: String,
    @SerializedName("deviceSecret") val deviceSecret: String?,
    @SerializedName("model") val model: String?,
    @SerializedName("appVersion") val appVersion: String?,
    @SerializedName("reset") val reset: Boolean? = null,
)

data class DeviceResponse(
    @SerializedName("mac") val mac: String,
    @SerializedName("deviceKey") val deviceKey: String,
    @SerializedName("deviceSecret") val deviceSecret: String?,
    @SerializedName("status") val status: String,
    @SerializedName("trialEndsAt") val trialEndsAt: String?,
    @SerializedName("licenseEndsAt") val licenseEndsAt: String?,
    @SerializedName("daysLeft") val daysLeft: Int?,
    @SerializedName("portalUrl") val portalUrl: String,
    @SerializedName("pairingUrl") val pairingUrl: String,
)

data class SyncResponse(
    @SerializedName("status") val status: String,
    @SerializedName("trialEndsAt") val trialEndsAt: String?,
    @SerializedName("daysLeft") val daysLeft: Int?,
    @SerializedName("playlistsVersion") val playlistsVersion: String,
    @SerializedName("playlists") val playlists: List<RemotePlaylist>,
)

data class RemotePlaylist(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    /** 'm3u_url' | 'xtream' | 'file' */
    @SerializedName("sourceType") val sourceType: String,
    @SerializedName("sourceUrl") val sourceUrl: String?,
    @SerializedName("xtream") val xtream: XtreamCredentials?,
    @SerializedName("epgUrl") val epgUrl: String?,
    @SerializedName("updatedAt") val updatedAt: String?,
) {
    /** Changes whenever the playlist definition changes server-side. */
    val importKey: String get() = "$id@$updatedAt"
}

data class XtreamCredentials(
    @SerializedName("host") val host: String,
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
)

data class ApiError(
    @SerializedName("error") val error: String?,
    @SerializedName("message") val message: String?,
)
