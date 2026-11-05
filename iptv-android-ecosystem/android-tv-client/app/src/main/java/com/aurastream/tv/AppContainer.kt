package com.aurastream.tv

import android.content.Context
import com.aurastream.tv.data.local.AppSettings
import com.aurastream.tv.data.local.AuraDatabase
import com.aurastream.tv.data.local.DeviceCredentialStore
import com.aurastream.tv.data.local.SettingsStore
import com.aurastream.tv.data.remote.NetworkModule
import com.aurastream.tv.data.remote.UserAgentProvider
import com.aurastream.tv.data.remote.XtreamApiClient
import com.aurastream.tv.data.repository.ChannelRepository
import com.aurastream.tv.data.repository.DeviceRepository
import com.aurastream.tv.data.repository.PlaylistRepository
import com.aurastream.tv.player.Media3PlayerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual dependency container (composition root). Kept deliberately simple: every
 * dependency is a process-wide singleton created lazily on first use.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Long-lived scope for work that must outlive a single screen (e.g. background sync). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val credentials by lazy { DeviceCredentialStore(appContext) }
    val settings by lazy { SettingsStore(appContext) }
    val userAgent = UserAgentProvider(AppSettings.DEFAULT_USER_AGENT)
    val network by lazy { NetworkModule(credentials, userAgent) }
    val database by lazy { AuraDatabase.build(appContext) }
    val xtream by lazy { XtreamApiClient(network.providerClient) }

    val deviceRepository by lazy { DeviceRepository(appContext, network.api, credentials, network.gson) }

    val playlistRepository by lazy {
        PlaylistRepository(
            api = network.api,
            db = database,
            settings = settings,
            providerClient = network.providerClient,
            xtream = xtream,
            gson = network.gson,
            cacheDir = appContext.cacheDir,
            deviceRepository = deviceRepository,
            backendBaseUrl = network.backendBaseUrl,
        )
    }

    val channelRepository by lazy { ChannelRepository(database, xtream, playlistRepository) }

    /** Shared player: Live TV mini-player and fullscreen player use the same instance. */
    val player by lazy { Media3PlayerAdapter(appContext, network, settings) }

    init {
        appScope.launch {
            settings.settings.map { it.userAgent }.distinctUntilChanged().collect { userAgent.value = it }
        }
    }
}
