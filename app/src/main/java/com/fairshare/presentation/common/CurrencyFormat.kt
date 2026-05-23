package com.fairshare.presentation.common

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

fun Long.centsToString(currency: String = "EUR"): String {
    return try {
        val nf = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            this.currency = Currency.getInstance(currency)
        }
        nf.format(this / 100.0)
    } catch (e: Exception) {
        String.format(Locale.getDefault(), "%.2f", this / 100.0)
    }
}

fun parseAmountToCents(text: String): Long? {
    val cleaned = text.trim().replace(',', '.').replace(" ", "")
    val v = cleaned.toDoubleOrNull() ?: return null
    return Math.round(v * 100)
}
