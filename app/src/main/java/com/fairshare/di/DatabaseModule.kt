package com.fairshare.di

import android.content.Context
import androidx.room.Room
import com.fairshare.data.local.FairShareDatabase
import com.fairshare.data.local.dao.CategoryDao
import com.fairshare.data.local.dao.EventDao
import com.fairshare.data.local.dao.ExpenseDao
import com.fairshare.data.local.dao.OperationDao
import com.fairshare.data.local.dao.ParticipantDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FairShareDatabase =
        Room.databaseBuilder(context, FairShareDatabase::class.java, "fairshare.db")
            // Schema is still iterating; nuke the DB on version bump (dev only).
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideEventDao(db: FairShareDatabase): EventDao = db.eventDao()
    @Provides fun provideParticipantDao(db: FairShareDatabase): ParticipantDao = db.participantDao()
    @Provides fun provideCategoryDao(db: FairShareDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideExpenseDao(db: FairShareDatabase): ExpenseDao = db.expenseDao()
    @Provides fun provideOperationDao(db: FairShareDatabase): OperationDao = db.operationDao()
}
