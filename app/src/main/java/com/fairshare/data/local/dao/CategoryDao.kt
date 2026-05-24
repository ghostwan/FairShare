package com.fairshare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fairshare.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE eventId = :eventId ORDER BY name COLLATE NOCASE")
    fun observeByEvent(eventId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE eventId = :eventId ORDER BY name COLLATE NOCASE")
    suspend fun getByEvent(eventId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: String)
}
