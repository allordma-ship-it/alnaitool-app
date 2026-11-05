package com.aurastream.tv.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["type", "orderIndex"])],
)
data class CategoryEntity(
    /** Namespaced id, e.g. `live:12` (Xtream) or `movie:Action` (M3U group-title). */
    @PrimaryKey val categoryId: String,
    val categoryName: String,
    /** 'live', 'movie' or 'series'. */
    val type: String,
    @ColumnInfo(defaultValue = "0") val isLocked: Boolean = false,
    /** Optional category-specific PIN; falls back to the global parental PIN when null. */
    val pinCode: String? = null,
    val orderIndex: Int = 0,
)
