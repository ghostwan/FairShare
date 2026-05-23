package com.fairshare.domain.usecase

import com.fairshare.domain.model.ReceiptItem
import java.util.UUID

/**
 * Expands receipt items with [ReceiptItem.quantity] > 1 into N items at unit price.
 * Rounding remainder is distributed cent-by-cent so the sum matches the original total.
 *
 * When [enabled] is false, the list is returned unchanged.
 */
class ExpandReceiptQuantitiesUseCase {

    operator fun invoke(items: List<ReceiptItem>, enabled: Boolean): List<ReceiptItem> {
        if (!enabled) return items
        return items.flatMap { item ->
            if (item.quantity <= 1) listOf(item)
            else {
                val base = item.priceCents / item.quantity
                val remainder = (item.priceCents - base * item.quantity).toInt()
                (0 until item.quantity).map { i ->
                    item.copy(
                        id = UUID.randomUUID().toString(),
                        priceCents = base + if (i < remainder) 1 else 0,
                        quantity = 1,
                    )
                }
            }
        }
    }
}
