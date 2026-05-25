package com.fairshare.di

import com.fairshare.domain.usecase.AssignReceiptItemsUseCase
import com.fairshare.domain.usecase.ComputeBalancesUseCase
import com.fairshare.domain.usecase.ComputeCategoryStatsUseCase
import com.fairshare.domain.usecase.ComputeSharesUseCase
import com.fairshare.domain.usecase.ExpandReceiptQuantitiesUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {
    @Provides fun provideComputeShares() = ComputeSharesUseCase()
    @Provides fun provideComputeBalances() = ComputeBalancesUseCase()
    @Provides fun provideComputeCategoryStats() = ComputeCategoryStatsUseCase()
    @Provides fun provideAssignReceipt() = AssignReceiptItemsUseCase()
    @Provides fun provideExpandReceiptQuantities() = ExpandReceiptQuantitiesUseCase()
}
