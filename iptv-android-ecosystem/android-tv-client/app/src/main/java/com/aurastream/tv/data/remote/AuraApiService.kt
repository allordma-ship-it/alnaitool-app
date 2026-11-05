package com.aurastream.tv.data.remote

import com.aurastream.tv.data.remote.dto.DeviceResponse
import com.aurastream.tv.data.remote.dto.HandshakeRequest
import com.aurastream.tv.data.remote.dto.SyncResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** AuraStream cloud backend. Device authentication headers are added by [DeviceAuthInterceptor]. */
interface AuraApiService {

    @POST("api/v1/device/handshake")
    suspend fun handshake(@Body body: HandshakeRequest): Response<DeviceResponse>

    @GET("api/v1/device/status")
    suspend fun status(): Response<DeviceResponse>

    @GET("api/v1/playlists/sync")
    suspend fun sync(): Response<SyncResponse>
}
