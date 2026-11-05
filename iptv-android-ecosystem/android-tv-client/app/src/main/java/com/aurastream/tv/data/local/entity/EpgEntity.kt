package com.aurastream.tv.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg",
    indices = [Index(value = ["channelId", "startTime"]), Index(value = ["endTime"])],
)
data class EpgEntity(
    @PrimaryKey(autoGenerate = true) val epgId: Long = 0,
    /** Matches [ChannelEntity.epgChannelId] (XMLTV channel id / tvg-id). */
    val channelId: String,
    val title: String,
    val description: String?,
    /** Epoch millis (UTC). */
    val startTime: Long,
    val endTime: Long,
)
