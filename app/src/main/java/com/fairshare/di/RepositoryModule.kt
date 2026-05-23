package com.fairshare.di

import com.fairshare.data.ocr.MlKitReceiptParser
import com.fairshare.data.repository.EventRepositoryImpl
import com.fairshare.data.repository.ExpenseRepositoryImpl
import com.fairshare.data.repository.ParticipantRepositoryImpl
import com.fairshare.data.repository.SettingsRepositoryImpl
import com.fairshare.domain.repository.EventRepository
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.domain.repository.ParticipantRepository
import com.fairshare.domain.repository.ReceiptParser
import com.fairshare.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository

    @Binds @Singleton
    abstract fun bindParticipantRepository(impl: ParticipantRepositoryImpl): ParticipantRepository

    @Binds @Singleton
    abstract fun bindExpenseRepository(impl: ExpenseRepositoryImpl): ExpenseRepository

    @Binds @Singleton
    abstract fun bindReceiptParser(impl: MlKitReceiptParser): ReceiptParser

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
