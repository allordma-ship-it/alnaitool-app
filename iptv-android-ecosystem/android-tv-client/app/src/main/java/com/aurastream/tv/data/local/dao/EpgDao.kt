package com.aurastream.tv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aurastream.tv.data.local.entity.EpgEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgDao {

    @Insert
    suspend fun insertAll(events: List<EpgEntity>)

    /** Current + following programmes for a channel. */
    @Query("SELECT * FROM epg WHERE channelId = :channelId AND endTime > :now ORDER BY startTime LIMIT :limit")
    fun observeUpcoming(channelId: String, now: Long, limit: Int = 2): Flow<List<EpgEntity>>

    @Query(
        "SELECT * FROM epg WHERE channelId = :channelId AND endTime > :from AND startTime < :to ORDER BY startTime",
    )
    fun observeRange(channelId: String, from: Long, to: Long): Flow<List<EpgEntity>>

    @Query("DELETE FROM epg WHERE endTime < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM epg WHERE channelId IN (:channelIds)")
    suspend fun deleteForChannels(channelIds: List<String>)

    @Query("SELECT COUNT(*) FROM epg")
    suspend fun count(): Int

    @Query("DELETE FROM epg")
    suspend fun clear()
}
