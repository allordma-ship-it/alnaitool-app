package com.aurastream.tv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aurastream.tv.data.local.entity.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes WHERE seriesStreamId = :seriesId ORDER BY season, episodeNum")
    fun observeForSeries(seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT COUNT(*) FROM episodes WHERE seriesStreamId = :seriesId")
    suspend fun countForSeries(seriesId: String): Int

    @Query("SELECT * FROM episodes WHERE episodeId = :episodeId")
    suspend fun get(episodeId: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE seriesStreamId = :seriesId ORDER BY season, episodeNum")
    suspend fun listForSeries(seriesId: String): List<EpisodeEntity>

    @Query("UPDATE episodes SET resumePositionMs = :positionMs WHERE episodeId = :episodeId")
    suspend fun updateResume(episodeId: String, positionMs: Long)

    @Query("DELETE FROM episodes")
    suspend fun clear()
}
