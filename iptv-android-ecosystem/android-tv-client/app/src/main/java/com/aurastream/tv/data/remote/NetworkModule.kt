package com.aurastream.tv.data.remote

import com.aurastream.tv.BuildConfig
import com.aurastream.tv.data.local.DeviceCredentialStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the user-configurable User-Agent. Updated from settings; read on every request.
 */
class UserAgentProvider(initial: String) {
    private val ref = AtomicReference(initial)
    var value: String
        get() = ref.get()
        set(v) = ref.set(v)
}

private class UserAgentInterceptor(private val provider: UserAgentProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Respect explicit per-request overrides (e.g. #EXTVLCOPT:http-user-agent).
        if (request.header(HEADER_UA_OVERRIDE) != null) {
            val ua = request.header(HEADER_UA_OVERRIDE)!!
            return chain.proceed(request.newBuilder().removeHeader(HEADER_UA_OVERRIDE).header("User-Agent", ua).build())
        }
        return chain.proceed(request.newBuilder().header("User-Agent", provider.value).build())
    }

    companion object {
        const val HEADER_UA_OVERRIDE = "X-Aura-UA"
    }
}

/**
 * Adds X-Device-Mac / X-Device-Secret **only** for requests to the AuraStream backend,
 * so device credentials can never leak to third-party playlist or stream hosts.
 */
class DeviceAuthInterceptor(
    private val backend: HttpUrl,
    private val credentials: DeviceCredentialStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val sameOrigin = url.host.equals(backend.host, ignoreCase = true) && url.port == backend.port && url.scheme == backend.scheme
        if (!sameOrigin) return chain.proceed(request)
        val mac = credentials.macAddress
        val secret = credentials.deviceSecret
        if (mac == null || secret == null) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("X-Device-Mac", mac)
                .header("X-Device-Secret", secret)
                .build(),
        )
    }
}

class NetworkModule(
    credentials: DeviceCredentialStore,
    val userAgent: UserAgentProvider,
) {
    val backendBaseUrl: HttpUrl = BuildConfig.API_BASE_URL.toHttpUrl()

    val gson: Gson = GsonBuilder().setLenient().create()

    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("X-Device-Secret")
    }

    /** Base client shared by all others (one connection pool / dispatcher). */
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor(UserAgentInterceptor(userAgent))
        .build()

    /** Backend API client (adds device auth for backend origin only). */
    val backendClient: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(DeviceAuthInterceptor(backendBaseUrl, credentials))
        .addInterceptor(logging)
        .build()

    /** IPTV provider client: long read timeout for huge playlists / XMLTV downloads. */
    val providerClient: OkHttpClient = baseClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .addInterceptor(DeviceAuthInterceptor(backendBaseUrl, credentials)) // file playlists are served by the backend
        .build()

    /** Streaming client used by Media3: no call timeout (live streams are endless). */
    val streamingClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    val api: AuraApiService = Retrofit.Builder()
        .baseUrl(backendBaseUrl)
        .client(backendClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(AuraApiService::class.java)

    companion object {
        const val HEADER_UA_OVERRIDE = UserAgentInterceptor.HEADER_UA_OVERRIDE
    }
}
