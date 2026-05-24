package com.fairshare.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.dao.ExpenseDao
import com.fairshare.data.local.dao.OperationDao
import com.fairshare.data.local.dao.ParticipantDao
import com.fairshare.data.local.entity.EventEntity
import com.fairshare.data.local.entity.ExpenseEntity
import com.fairshare.data.local.entity.ExpenseItemAssignmentEntity
import com.fairshare.data.local.entity.ExpenseItemEntity
import com.fairshare.data.local.entity.ExpenseShareEntity
import com.fairshare.data.local.entity.OperationEntity
import com.fairshare.data.local.entity.ParticipantEntity

@Database(
    entities = [
        EventEntity::class,
        ParticipantEntity::class,
        ExpenseEntity::class,
        ExpenseShareEntity::class,
        ExpenseItemEntity::class,
        ExpenseItemAssignmentEntity::class,
        OperationEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class FairShareDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun participantDao(): ParticipantDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun operationDao(): OperationDao
}
