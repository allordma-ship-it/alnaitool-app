package com.aurastream.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aurastream.tv.data.local.dao.CategoryDao
import com.aurastream.tv.data.local.dao.ChannelDao
import com.aurastream.tv.data.local.dao.EpgDao
import com.aurastream.tv.data.local.dao.EpisodeDao
import com.aurastream.tv.data.local.entity.CategoryEntity
import com.aurastream.tv.data.local.entity.ChannelEntity
import com.aurastream.tv.data.local.entity.EpgEntity
import com.aurastream.tv.data.local.entity.EpisodeEntity

@Database(
    entities = [ChannelEntity::class, CategoryEntity::class, EpgEntity::class, EpisodeEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun categoryDao(): CategoryDao
    abstract fun epgDao(): EpgDao
    abstract fun episodeDao(): EpisodeDao

    companion object {
        fun build(context: Context): AuraDatabase =
            Room.databaseBuilder(context, AuraDatabase::class.java, "aurastream.db")
                // Write-ahead logging lets the UI read while large imports write.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // The DB is a cache of remote playlists: it can always be rebuilt by a re-sync.
                .fallbackToDestructiveMigration()
                .build()
    }
}
