package com.aurastream.tv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aurastream.tv.data.local.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY orderIndex")
    fun observeByCategory(categoryId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE streamType = :type ORDER BY orderIndex")
    fun observeByType(type: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE streamType = :type AND isFavorite = 1 ORDER BY name COLLATE NOCASE")
    fun observeFavorites(type: String): Flow<List<ChannelEntity>>

    @Query(
        "SELECT * FROM channels WHERE streamType IN (:types) AND lastWatchedAt IS NOT NULL " +
            "ORDER BY lastWatchedAt DESC LIMIT :limit",
    )
    fun observeRecent(types: List<String>, limit: Int): Flow<List<ChannelEntity>>

    @Query(
        "SELECT * FROM channels WHERE streamType = :type AND name LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY orderIndex LIMIT :limit",
    )
    fun search(type: String, query: String, limit: Int = 300): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE streamId = :streamId")
    suspend fun get(streamId: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE streamId = :streamId")
    fun observe(streamId: String): Flow<ChannelEntity?>

    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY orderIndex")
    suspend fun listByCategory(categoryId: String): List<ChannelEntity>

    @Query("SELECT streamId FROM channels WHERE isFavorite = 1")
    suspend fun favoriteIds(): List<String>

    @Query("UPDATE channels SET isFavorite = 1 WHERE streamId IN (:ids)")
    suspend fun markFavorites(ids: List<String>)

    @Query("UPDATE channels SET isFavorite = NOT isFavorite WHERE streamId = :streamId")
    suspend fun toggleFavorite(streamId: String)

    @Query("UPDATE channels SET resumePositionMs = :positionMs, lastWatchedAt = :watchedAt WHERE streamId = :streamId")
    suspend fun updateWatchState(streamId: String, positionMs: Long, watchedAt: Long)

    @Query(
        "UPDATE channels SET plot = COALESCE(:plot, plot), genre = COALESCE(:genre, genre), " +
            "releaseYear = COALESCE(:year, releaseYear), backdropUrl = COALESCE(:backdrop, backdropUrl), " +
            "rating = COALESCE(:rating, rating), durationSecs = COALESCE(:duration, durationSecs) WHERE streamId = :streamId",
    )
    suspend fun updateMetadata(
        streamId: String,
        plot: String?,
        genre: String?,
        year: String?,
        backdrop: String?,
        rating: String?,
        duration: Int?,
    )

    @Query("SELECT DISTINCT epgChannelId FROM channels WHERE epgChannelId IS NOT NULL AND epgChannelId != ''")
    suspend fun epgChannelIds(): List<String>

    @Query("SELECT COUNT(*) FROM channels WHERE streamType = :type")
    fun observeCount(type: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int

    @Query("DELETE FROM channels")
    suspend fun clear()
}
