package com.fairshare.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.dao.ExpenseDao
import com.fairshare.data.local.dao.ParticipantDao
import com.fairshare.data.local.entity.EventEntity
import com.fairshare.data.local.entity.ExpenseEntity
import com.fairshare.data.local.entity.ExpenseShareEntity
import com.fairshare.data.local.entity.ParticipantEntity

@Database(
    entities = [
        EventEntity::class,
        ParticipantEntity::class,
        ExpenseEntity::class,
        ExpenseShareEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class FairShareDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun participantDao(): ParticipantDao
    abstract fun expenseDao(): ExpenseDao
}
