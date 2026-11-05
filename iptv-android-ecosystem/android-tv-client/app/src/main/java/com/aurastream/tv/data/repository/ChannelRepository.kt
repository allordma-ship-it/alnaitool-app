package com.aurastream.tv.data.repository

import com.aurastream.tv.data.local.AuraDatabase
import com.aurastream.tv.data.local.dao.CategoryWithCount
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.EpgEntity
import com.aurastream.tv.data.local.entity.EpisodeEntity
import com.aurastream.tv.data.local.entity.StreamType
import com.aurastream.tv.data.remote.XtreamApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Virtual categories shown on top of the provider categories. */
object VirtualCategory {
    fun favorites(type: String) = "__fav__:$type"
    fun all(type: String) = "__all__:$type"
    fun isFavorites(id: String) = id.startsWith("__fav__:")
    fun isAll(id: String) = id.startsWith("__all__:")
    fun isVirtual(id: String) = isFavorites(id) || isAll(id)
    fun typeOf(id: String) = id.substringAfter(':')
}

data class NowNext(val now: EpgEntity?, val next: EpgEntity?) {
    fun progress(at: Long = System.currentTimeMillis()): Float {
        val n = now ?: return 0f
        val span = (n.endTime - n.startTime).coerceAtLeast(1)
        return ((at - n.startTime).toFloat() / span).coerceIn(0f, 1f)
    }
}

/** Read side of the local library + on-demand provider lookups (series episodes, VOD details). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRepository(
    private val db: AuraDatabase,
    private val xtream: XtreamApiClient,
    private val playlists: PlaylistRepository,
) {
    private val channelDao = db.channelDao()
    private val categoryDao = db.categoryDao()
    private val epgDao = db.epgDao()
    private val episodeDao = db.episodeDao()

    fun categories(type: String): Flow<List<CategoryWithCount>> = categoryDao.observeByType(type)

    fun channels(categoryId: String): Flow<List<ChannelEntity>> = when {
        VirtualCategory.isFavorites(categoryId) -> channelDao.observeFavorites(VirtualCategory.typeOf(categoryId))
        VirtualCategory.isAll(categoryId) -> channelDao.observeByType(VirtualCategory.typeOf(categoryId))
        else -> channelDao.observeByCategory(categoryId)
    }

    fun search(type: String, query: String): Flow<List<ChannelEntity>> {
        val q = query.trim()
        if (q.length < 2) return flowOf(emptyList())
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return channelDao.search(type, escaped)
    }

    fun recent(limit: Int = 20): Flow<List<ChannelEntity>> =
        channelDao.observeRecent(listOf(StreamType.LIVE, StreamType.MOVIE, StreamType.SERIES), limit)

    fun count(type: String): Flow<Int> = channelDao.observeCount(type)

    fun observe(streamId: String): Flow<ChannelEntity?> = channelDao.observe(streamId)

    suspend fun get(streamId: String): ChannelEntity? = channelDao.get(streamId)

    suspend fun siblings(channel: ChannelEntity): List<ChannelEntity> = channelDao.listByCategory(channel.categoryId)

    suspend fun toggleFavorite(streamId: String) = channelDao.toggleFavorite(streamId)

    suspend fun setCategoryLocked(categoryId: String, locked: Boolean) = categoryDao.setLocked(categoryId, locked)

    suspend fun category(categoryId: String) = categoryDao.get(categoryId)

    suspend fun saveProgress(streamId: String, positionMs: Long) =
        channelDao.updateWatchState(streamId, positionMs, System.currentTimeMillis())

    suspend fun saveEpisodeProgress(seriesId: String, episodeId: String, positionMs: Long) {
        episodeDao.updateResume(episodeId, positionMs)
        channelDao.updateWatchState(seriesId, 0, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------- EPG

    /** Minute ticker so "now/next" rolls over without user interaction. */
    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    fun nowNext(epgChannelId: String?): Flow<NowNext> {
        if (epgChannelId.isNullOrBlank()) return flowOf(NowNext(null, null))
        return minuteTicker.flatMapLatest { now ->
            epgDao.observeUpcoming(epgChannelId, now, 2).map { list ->
                val current = list.firstOrNull { it.startTime <= now }
                NowNext(current, list.firstOrNull { it !== current && it.startTime >= (current?.endTime ?: now) })
            }
        }
    }

    fun schedule(epgChannelId: String, from: Long, to: Long): Flow<List<EpgEntity>> = epgDao.observeRange(epgChannelId, from, to)

    // ---------------------------------------------------------------- series

    fun episodes(seriesStreamId: String): Flow<List<EpisodeEntity>> = episodeDao.observeForSeries(seriesStreamId)

    suspend fun episode(episodeId: String) = episodeDao.get(episodeId)

    suspend fun episodeList(seriesStreamId: String) = episodeDao.listForSeries(seriesStreamId)

    /** Lazily loads Xtream series episodes on first open (M3U series are imported eagerly). */
    suspend fun ensureEpisodes(series: ChannelEntity) = withContext(Dispatchers.IO) {
        if (episodeDao.countForSeries(series.streamId) > 0) return@withContext
        val creds = playlists.activeXtream() ?: return@withContext
        val remoteId = series.remoteId ?: return@withContext
        val episodes = xtream.seriesEpisodes(creds, remoteId).map { e ->
            EpisodeEntity(
                episodeId = "ep:${e.id}",
                seriesStreamId = series.streamId,
                season = e.season,
                episodeNum = e.episodeNum,
                title = e.title,
                streamUrl = xtream.episodeUrl(creds, e.id, e.extension),
                plot = e.plot,
                durationSecs = e.durationSecs,
                imageUrl = e.imageUrl,
            )
        }
        if (episodes.isNotEmpty()) episodeDao.insertAll(episodes)
    }

    /** Fills plot / genre / backdrop for Xtream movies the first time they are opened. */
    suspend fun ensureVodDetails(movie: ChannelEntity) = withContext(Dispatchers.IO) {
        if (movie.streamType != StreamType.MOVIE || movie.plot != null) return@withContext
        val creds = playlists.activeXtream() ?: return@withContext
        val info = runCatching { xtream.vodInfo(creds, movie.remoteId ?: return@withContext) }.getOrNull() ?: return@withContext
        channelDao.updateMetadata(movie.streamId, info.plot ?: "", info.genre, info.releaseYear, info.backdropUrl, info.rating, info.durationSecs)
    }

    /** Merged category list: Favorites + All + provider categories. */
    fun categoriesWithVirtual(type: String, favoritesLabel: String, allLabel: String): Flow<List<CategoryWithCount>> =
        combine(categories(type), channelDao.observeFavorites(type).map { it.size }, count(type)) { cats, favs, all ->
            buildList {
                add(CategoryWithCount(VirtualCategory.favorites(type), favoritesLabel, type, false, null, -2, favs))
                add(CategoryWithCount(VirtualCategory.all(type), allLabel, type, false, null, -1, all))
                addAll(cats.filter { it.channelCount > 0 })
            }
        }.flowOn(Dispatchers.Default)
}
