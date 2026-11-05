package com.aurastream.tv.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    indices = [Index(value = ["seriesStreamId", "season", "episodeNum"])],
)
data class EpisodeEntity(
    @PrimaryKey val episodeId: String,
    /** [ChannelEntity.streamId] of the parent series. */
    val seriesStreamId: String,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val streamUrl: String,
    val plot: String? = null,
    val durationSecs: Int? = null,
    val imageUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val resumePositionMs: Long = 0,
)
