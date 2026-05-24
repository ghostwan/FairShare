package com.fairshare.di

import com.fairshare.data.ocr.GeminiReceiptParser
import com.fairshare.data.ocr.MlKitReceiptParser
import com.fairshare.data.repository.CategoryRepositoryImpl
import com.fairshare.data.repository.EventRepositoryImpl
import com.fairshare.data.repository.ExpenseRepositoryImpl
import com.fairshare.data.repository.ParticipantRepositoryImpl
import com.fairshare.data.repository.SettingsRepositoryImpl
import com.fairshare.data.sync.WorkerCloudTransport
import com.fairshare.domain.repository.CategoryRepository
import com.fairshare.domain.repository.CloudTransport
import com.fairshare.domain.repository.EventRepository
import com.fairshare.domain.repository.ExpenseRepository
import com.fairshare.domain.repository.ParticipantRepository
import com.fairshare.domain.repository.ReceiptParser
import com.fairshare.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository

    @Binds @Singleton
    abstract fun bindParticipantRepository(impl: ParticipantRepositoryImpl): ParticipantRepository

    @Binds @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds @Singleton
    abstract fun bindExpenseRepository(impl: ExpenseRepositoryImpl): ExpenseRepository

    @Binds @Singleton @MlKit
    abstract fun bindMlKitReceiptParser(impl: MlKitReceiptParser): ReceiptParser

    @Binds @Singleton @Gemini
    abstract fun bindGeminiReceiptParser(impl: GeminiReceiptParser): ReceiptParser

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindCloudTransport(impl: WorkerCloudTransport): CloudTransport
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Gemini multimodal calls can be slow on large images.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
