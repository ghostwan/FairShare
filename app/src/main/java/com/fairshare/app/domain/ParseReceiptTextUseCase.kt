package com.fairshare.app.domain

import com.fairshare.app.domain.model.ReceiptItem

class ParseReceiptTextUseCase {
    operator fun invoke(rawText: String): List<ReceiptItem> {
        return rawText
            .lineSequence()
            .map { it.trim() }
            .filter { it.length >= 4 }
            .mapNotNull(::parseLine)
            .filterNot { it.label.contains("total", ignoreCase = true) || it.label.contains("tva", ignoreCase = true) }
            .distinctBy { it.label.lowercase() to it.amount }
            .toList()
    }

    private fun parseLine(line: String): ReceiptItem? {
        val match = priceRegex.find(line) ?: return null
        val amount = match.value.replace(',', '.').toDoubleOrNull() ?: return null
        val label = line.removeRange(match.range).trim(' ', '.', '-', ':')
        if (label.length < 2 || amount <= 0.0) return null

        return ReceiptItem(
            id = "item-${System.nanoTime()}-${label.hashCode()}",
            label = label.replace(Regex("\\s+"), " "),
            amount = amount,
            assignedParticipantIds = emptySet()
        )
    }

    private companion object {
        val priceRegex = Regex("\\d+[,.]\\d{2}")
    }
}
