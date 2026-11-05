package com.aurastream.tv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aurastream.tv.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/** Category plus number of channels it contains (computed on the fly). */
data class CategoryWithCount(
    val categoryId: String,
    val categoryName: String,
    val type: String,
    val isLocked: Boolean,
    val pinCode: String?,
    val orderIndex: Int,
    val channelCount: Int,
)

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query(
        """
        SELECT c.categoryId, c.categoryName, c.type, c.isLocked, c.pinCode, c.orderIndex,
               (SELECT COUNT(*) FROM channels ch WHERE ch.categoryId = c.categoryId) AS channelCount
        FROM categories c
        WHERE c.type = :type
        ORDER BY c.orderIndex
        """,
    )
    fun observeByType(type: String): Flow<List<CategoryWithCount>>

    @Query("SELECT * FROM categories WHERE categoryId = :categoryId")
    suspend fun get(categoryId: String): CategoryEntity?

    @Query("UPDATE categories SET isLocked = :locked WHERE categoryId = :categoryId")
    suspend fun setLocked(categoryId: String, locked: Boolean)

    @Query("SELECT categoryId FROM categories WHERE isLocked = 1")
    suspend fun lockedIds(): List<String>

    @Query("UPDATE categories SET isLocked = 1 WHERE categoryId IN (:ids)")
    suspend fun markLocked(ids: List<String>)

    @Query("DELETE FROM categories")
    suspend fun clear()
}
