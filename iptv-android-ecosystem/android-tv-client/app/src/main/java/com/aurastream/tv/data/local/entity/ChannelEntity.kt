package com.aurastream.tv.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A playable item of the active playlist: live channel, movie or series container.
 * Series episodes live in [EpisodeEntity] and reference [streamId].
 */
@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["categoryId", "orderIndex"]),
        Index(value = ["streamType", "isFavorite"]),
        Index(value = ["epgChannelId"]),
        Index(value = ["name"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey val streamId: String,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val categoryId: String,
    val epgChannelId: String?,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    val orderIndex: Int,
    /** One of [StreamType.LIVE], [StreamType.MOVIE], [StreamType.SERIES]. */
    val streamType: String = StreamType.LIVE,
    // --- VOD metadata (null for live channels) ---
    val plot: String? = null,
    val releaseYear: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    val backdropUrl: String? = null,
    val durationSecs: Int? = null,
    /** Provider-side id used for on-demand detail lookups (Xtream vod_id / series_id). */
    val remoteId: String? = null,
    // --- Per-stream HTTP overrides from #EXTVLCOPT ---
    val httpUserAgent: String? = null,
    val httpReferrer: String? = null,
    // --- Watch state ---
    @ColumnInfo(defaultValue = "0") val resumePositionMs: Long = 0,
    val lastWatchedAt: Long? = null,
)
