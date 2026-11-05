package com.aurastream.tv.data.repository

import android.util.Log
import com.aurastream.tv.data.local.AuraDatabase
import com.aurastream.tv.data.local.SettingsStore
import com.aurastream.tv.data.local.entity.CategoryEntity
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.EpgEntity
import com.aurastream.tv.data.local.entity.EpisodeEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.remote.AuraApiService
import com.aurastream.tv.data.remote.M3uEntry
import com.aurastream.tv.data.remote.M3uStreamParser
import com.aurastream.tv.data.remote.XmltvParser
import com.aurastream.tv.data.remote.XtreamApiClient
import com.aurastream.tv.data.remote.dto.RemotePlaylist
import com.aurastream.tv.data.remote.dto.SyncResponse
import com.aurastream.tv.data.remote.dto.XtreamCredentials
import com.aurastream.tv.data.remote.firstString
import com.aurastream.tv.data.remote.int
import com.aurastream.tv.data.remote.str
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

enum class ImportStage { CONNECTING, DOWNLOADING, LIVE, MOVIES, SERIES, EPG }

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val playlistName: String, val stage: ImportStage, val items: Int) : ImportState
    data class Failed(val message: String) : ImportState
}

sealed interface SyncOutcome {
    data class Ready(val playlist: RemotePlaylist, val imported: Boolean) : SyncOutcome
    data object NoPlaylists : SyncOutcome
    data class LicenseExpired(val message: String?) : SyncOutcome
    /** Backend unreachable; [hasLocalData] tells whether the cached library is usable. */
    data class Offline(val hasLocalData: Boolean, val cause: Throwable) : SyncOutcome
    data class Failed(val message: String, val hasLocalData: Boolean) : SyncOutcome
}

/**
 * Synchronises playlist definitions from the AuraStream backend and imports the
 * active playlist (M3U or Xtream Codes) into Room.
 *
 * Imports are streamed and inserted in batches on [Dispatchers.IO]; the UI observes
 * [importState] for progress. Favourites, locked categories and watch state survive
 * re-imports because stream ids are deterministic.
 */
class PlaylistRepository(
    private val api: AuraApiService,
    private val db: AuraDatabase,
    private val settings: SettingsStore,
    private val providerClient: OkHttpClient,
    private val xtream: XtreamApiClient,
    private val gson: Gson,
    private val cacheDir: File,
    private val deviceRepository: DeviceRepository,
    private val backendBaseUrl: HttpUrl,
) {
    private val mutex = Mutex()
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val playlistListType = object : TypeToken<List<RemotePlaylist>>() {}.type

    val playlists: Flow<List<RemotePlaylist>> = settings.settings
        .map { decodePlaylists(it.playlistsJson) }
        .distinctUntilChanged()

    val activePlaylist: Flow<RemotePlaylist?> = settings.settings
        .map { s -> decodePlaylists(s.playlistsJson).let { list -> list.firstOrNull { it.id == s.activePlaylistId } ?: list.firstOrNull() } }
        .distinctUntilChanged()

    suspend fun hasLocalData(): Boolean = withContext(Dispatchers.IO) { db.channelDao().count() > 0 }

    /** Credentials of the active playlist when it is an Xtream account (used for on-demand lookups). */
    suspend fun activeXtream(): XtreamCredentials? = currentActive()?.xtream

    suspend fun currentActive(): RemotePlaylist? {
        val s = settings.current()
        val list = decodePlaylists(s.playlistsJson)
        return list.firstOrNull { it.id == s.activePlaylistId } ?: list.firstOrNull()
    }

    /**
     * Pulls playlist descriptors from the backend and (re)imports the active playlist
     * when it changed, when [forceImport] is set or when the local library is empty.
     */
    suspend fun sync(forceImport: Boolean = false): SyncOutcome = mutex.withLock {
        val response: SyncResponse = try {
            val res = api.sync()
            if (res.code() == 402) {
                deviceRepository.updateLicense(LicenseStatus.EXPIRED, 0)
                return@withLock SyncOutcome.LicenseExpired(null)
            }
            res.bodyOrThrow(gson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return@withLock SyncOutcome.Failed(e.message ?: "Sync failed", hasLocalData())
        } catch (e: IOException) {
            return@withLock SyncOutcome.Offline(hasLocalData(), e)
        }

        deviceRepository.updateLicense(LicenseStatus.from(response.status), response.daysLeft)
        settings.saveSyncResult(response.playlistsVersion, gson.toJson(response.playlists))

        if (response.playlists.isEmpty()) return@withLock SyncOutcome.NoPlaylists

        val current = settings.current()
        val active = response.playlists.firstOrNull { it.id == current.activePlaylistId } ?: response.playlists.first()
        if (active.id != current.activePlaylistId) settings.setActivePlaylistId(active.id)

        val needsImport = forceImport || current.importedPlaylistKey != active.importKey || !hasLocalData()
        if (!needsImport) {
            // Refresh a stale guide in the background of the sync (non fatal).
            if (System.currentTimeMillis() - current.lastSyncAt > EPG_MAX_AGE_MS) runCatching { importEpg(active) }
            return@withLock SyncOutcome.Ready(active, imported = false)
        }

        try {
            importLocked(active)
            SyncOutcome.Ready(active, imported = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            _importState.value = ImportState.Failed(e.message ?: e.javaClass.simpleName)
            SyncOutcome.Failed(e.message ?: "Import failed", hasLocalData())
        }
    }

    /** Switches the active playlist and imports it. */
    suspend fun selectPlaylist(id: String): SyncOutcome {
        settings.setActivePlaylistId(id)
        return sync(forceImport = true)
    }

    suspend fun refreshEpg() = mutex.withLock { currentActive()?.let { importEpg(it) } }

    // ------------------------------------------------------------------ import

    private suspend fun importLocked(playlist: RemotePlaylist) = withContext(Dispatchers.IO) {
        _importState.value = ImportState.Running(playlist.name, ImportStage.CONNECTING, 0)

        // Preserve user state across the wipe.
        val favorites = db.channelDao().favoriteIds()
        val locked = db.categoryDao().lockedIds()

        when (playlist.sourceType) {
            "xtream" -> importXtream(playlist, requireNotNull(playlist.xtream) { "Missing Xtream credentials" })
            else -> importM3u(playlist)
        }

        favorites.chunked(500).forEach { db.channelDao().markFavorites(it) }
        locked.chunked(500).forEach { db.categoryDao().markLocked(it) }
        settings.setImportedPlaylistKey(playlist.importKey)

        runCatching { importEpg(playlist) }.onFailure { Log.w(TAG, "EPG import failed", it) }
        _importState.value = ImportState.Idle
    }

    private suspend fun clearLibrary() {
        db.epgDao().clear()
        db.episodeDao().clear()
        db.channelDao().clear()
        db.categoryDao().clear()
    }

    private suspend fun importM3u(playlist: RemotePlaylist) {
        // Uploaded files are served by the AuraStream backend. Resolve against the configured API
        // origin so the device-auth headers are attached (and never sent to another host).
        val url = if (playlist.sourceType == "file") {
            backendBaseUrl.resolve("api/v1/playlists/${playlist.id}/content")?.toString()
        } else {
            playlist.sourceUrl
        } ?: throw IOException("Playlist has no URL")
        _importState.value = ImportState.Running(playlist.name, ImportStage.DOWNLOADING, 0)

        // Download fully before touching the DB so a network failure never wipes the library.
        val file = File(cacheDir, "playlist-import.m3u")
        providerClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Playlist download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty playlist")
            file.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
        }

        try {
            clearLibrary()
            val importer = M3uImporter(playlist.name)
            file.bufferedReader(Charsets.UTF_8, 64 * 1024).use { reader ->
                val doc = M3uStreamParser().parse(reader)
                importer.epgUrls = doc.header.epgUrls
                for (entry in doc.entries) {
                    importer.add(entry)
                    if (importer.pendingSize >= BATCH) {
                        coroutineContext.ensureActive()
                        importer.flush()
                    }
                }
            }
            importer.flush()
            importer.flushCategories()
            if (importer.total == 0) throw IOException("The playlist contains no playable entries")
            m3uHeaderEpg = importer.epgUrls.firstOrNull()
        } finally {
            file.delete()
        }
    }

    /** EPG url discovered from the M3U header (`url-tvg`), used when the backend has none. */
    @Volatile private var m3uHeaderEpg: String? = null

    private inner class M3uImporter(private val playlistName: String) {
        var epgUrls: List<String> = emptyList()
        var total = 0
        private val categories = LinkedHashMap<String, CategoryEntity>()
        private val channels = ArrayList<ChannelEntity>(BATCH)
        private val episodes = ArrayList<EpisodeEntity>(BATCH)
        private val seriesCreated = HashSet<String>()
        private var order = 0
        val pendingSize get() = channels.size + episodes.size

        fun add(e: M3uEntry) {
            val type = PlaylistImportRules.classify(e)
            val group = e.groupTitle?.takeIf { it.isNotBlank() } ?: UNCATEGORIZED
            val categoryId = "$type:$group"
            categories.getOrPut(categoryId) { CategoryEntity(categoryId, group, type, orderIndex = categories.size) }

            if (type == StreamType.SERIES) {
                val m = PlaylistImportRules.SERIES_PATTERN.find(e.title)
                val seriesName = m?.groupValues?.get(1)?.trim()?.trimEnd('-', ':', '|', '.')?.trim()?.ifBlank { null } ?: e.title
                val season = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
                val episode = m?.groupValues?.get(3)?.toIntOrNull() ?: (order + 1)
                val seriesId = "s:" + PlaylistImportRules.stableId("$categoryId|$seriesName")
                if (seriesCreated.add(seriesId)) {
                    channels += ChannelEntity(
                        streamId = seriesId, name = seriesName, logoUrl = e.tvgLogo, streamUrl = "",
                        categoryId = categoryId, epgChannelId = null, orderIndex = order++, streamType = StreamType.SERIES,
                    )
                }
                episodes += EpisodeEntity(
                    episodeId = "e:" + PlaylistImportRules.stableId(e.url + "|" + e.title),
                    seriesStreamId = seriesId, season = season, episodeNum = episode,
                    title = e.title, streamUrl = e.url, imageUrl = e.tvgLogo,
                )
            } else {
                channels += ChannelEntity(
                    streamId = (if (type == StreamType.LIVE) "l:" else "m:") + PlaylistImportRules.stableId("$categoryId|${e.title}|${e.url}"),
                    name = e.title,
                    logoUrl = e.tvgLogo,
                    streamUrl = e.url,
                    categoryId = categoryId,
                    epgChannelId = e.tvgId ?: e.tvgName,
                    orderIndex = order++,
                    streamType = type,
                    durationSecs = e.durationSecs.takeIf { it > 0 },
                    httpUserAgent = e.userAgent,
                    httpReferrer = e.referrer,
                )
            }
            total++
        }

        suspend fun flush() {
            if (categories.isNotEmpty()) flushCategories()
            if (channels.isNotEmpty()) db.channelDao().insertAll(ArrayList(channels)).also { channels.clear() }
            if (episodes.isNotEmpty()) db.episodeDao().insertAll(ArrayList(episodes)).also { episodes.clear() }
            _importState.value = ImportState.Running(playlistName, ImportStage.LIVE, total)
        }

        suspend fun flushCategories() = db.categoryDao().insertAll(categories.values.toList())
    }

    private suspend fun importXtream(playlist: RemotePlaylist, c: XtreamCredentials) {
        xtream.authenticate(c) // fails fast on bad credentials, before the library is wiped
        val categoriesByType = mapOf(
            StreamType.LIVE to xtream.categories(c, StreamType.LIVE),
            StreamType.MOVIE to xtream.categories(c, StreamType.MOVIE),
            StreamType.SERIES to xtream.categories(c, StreamType.SERIES),
        )
        clearLibrary()

        val known = HashSet<String>()
        categoriesByType.forEach { (type, list) ->
            val entities = list.mapIndexed { i, cat -> CategoryEntity("$type:${cat.id}", cat.name, type, orderIndex = i) }
            known += entities.map { it.categoryId }
            db.categoryDao().insertAll(entities)
        }

        var order = 0
        val stages = listOf(
            StreamType.LIVE to ImportStage.LIVE,
            StreamType.MOVIE to ImportStage.MOVIES,
            StreamType.SERIES to ImportStage.SERIES,
        )
        for ((type, stage) in stages) {
            var count = 0
            _importState.value = ImportState.Running(playlist.name, stage, 0)
            xtream.streamCatalogue(c, type, BATCH) { batch ->
                val entities = batch.mapNotNull { o -> o.toChannel(c, type, order++) }
                val orphanCategories = entities.map { it.categoryId }.filter { it !in known }.toSet()
                if (orphanCategories.isNotEmpty()) {
                    known += orphanCategories
                    db.categoryDao().insertAll(
                        orphanCategories.map { id ->
                            CategoryEntity(id, if (id.endsWith(":$UNCATEGORIZED")) UNCATEGORIZED else id.substringAfter(':'), type, orderIndex = Int.MAX_VALUE / 2)
                        },
                    )
                }
                db.channelDao().insertAll(entities)
                count += entities.size
                _importState.value = ImportState.Running(playlist.name, stage, count)
            }
        }
    }

    private fun JsonObject.toChannel(c: XtreamCredentials, type: String, order: Int): ChannelEntity? {
        val categoryId = "$type:" + (str("category_id") ?: UNCATEGORIZED)
        val name = str("name")?.trim() ?: return null
        return when (type) {
            StreamType.LIVE -> {
                val id = str("stream_id") ?: return null
                ChannelEntity(
                    streamId = "live:$id", name = name, logoUrl = str("stream_icon"),
                    streamUrl = xtream.liveUrl(c, id), categoryId = categoryId,
                    epgChannelId = str("epg_channel_id"), orderIndex = int("num") ?: order,
                    streamType = StreamType.LIVE, remoteId = id,
                )
            }
            StreamType.MOVIE -> {
                val id = str("stream_id") ?: return null
                ChannelEntity(
                    streamId = "movie:$id", name = name, logoUrl = str("stream_icon"),
                    streamUrl = xtream.movieUrl(c, id, str("container_extension") ?: "mp4"),
                    categoryId = categoryId, epgChannelId = null, orderIndex = order,
                    streamType = StreamType.MOVIE, rating = str("rating"), remoteId = id,
                    releaseYear = str("year") ?: str("releaseDate")?.take(4),
                )
            }
            else -> {
                val id = str("series_id") ?: return null
                ChannelEntity(
                    streamId = "series:$id", name = name, logoUrl = str("cover"), streamUrl = "",
                    categoryId = categoryId, epgChannelId = null, orderIndex = order,
                    streamType = StreamType.SERIES, plot = str("plot"), genre = str("genre"),
                    releaseYear = (str("releaseDate") ?: str("release_date") ?: str("year"))?.take(4),
                    rating = str("rating"), backdropUrl = firstString("backdrop_path"), remoteId = id,
                )
            }
        }
    }

    // --------------------------------------------------------------------- EPG

    private suspend fun importEpg(playlist: RemotePlaylist) = withContext(Dispatchers.IO) {
        val url = playlist.epgUrl
            ?: playlist.xtream?.let { xtream.xmltvUrl(it) }
            ?: m3uHeaderEpg
            ?: return@withContext
        val wanted = db.channelDao().epgChannelIds().toHashSet()
        if (wanted.isEmpty()) return@withContext

        _importState.value = ImportState.Running(playlist.name, ImportStage.EPG, 0)
        val now = System.currentTimeMillis()
        val windowStart = now - 3 * HOUR
        val windowEnd = now + 36 * HOUR

        val buffer = ArrayList<EpgEntity>(EPG_BATCH)
        var count = 0
        var cleared = false
        providerClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("EPG download failed: HTTP ${response.code}")
            val body = response.body ?: return@use
            val pending = ArrayList<List<EpgEntity>>()
            XmltvParser().parse(body.byteStream(), wanted, windowStart, windowEnd) { p ->
                buffer += EpgEntity(channelId = p.channelId, title = p.title, description = p.description, startTime = p.start, endTime = p.end)
                if (buffer.size >= EPG_BATCH) {
                    pending += ArrayList(buffer)
                    buffer.clear()
                }
            }
            if (buffer.isNotEmpty()) pending += ArrayList(buffer)
            // The pull parser callback is not suspending; insert after parsing the windowed set.
            for (chunk in pending) {
                if (!cleared) {
                    db.epgDao().clear()
                    cleared = true
                }
                db.epgDao().insertAll(chunk)
                count += chunk.size
                _importState.value = ImportState.Running(playlist.name, ImportStage.EPG, count)
            }
        }
        db.epgDao().deleteOlderThan(now - 6 * HOUR)
        _importState.value = ImportState.Idle
    }

    // ------------------------------------------------------------------ helpers

    private fun decodePlaylists(json: String?): List<RemotePlaylist> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching { gson.fromJson<List<RemotePlaylist>>(json, playlistListType) }.getOrNull().orEmpty()

    companion object {
        private const val TAG = "PlaylistRepository"
        private const val BATCH = 1000
        private const val EPG_BATCH = 2000
        private const val HOUR = 3_600_000L
        private const val EPG_MAX_AGE_MS = 12 * HOUR
        const val UNCATEGORIZED = "Uncategorized"

    }
}
