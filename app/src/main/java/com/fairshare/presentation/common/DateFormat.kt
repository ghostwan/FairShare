package com.fairshare.presentation.common

import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/**
 * Returns the epoch-millis corresponding to 00:00:00 (system default zone)
 * of the day this timestamp falls into. Used to group timeline rows by day.
 */
fun Long.toStartOfDay(zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(this)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

/** Medium-style date label, e.g. "24 mai 2026". */
fun Long.toMediumDateLabel(locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(this))

/**
 * Human-friendly header for the timeline: "Aujourd'hui", "Hier",
 * "lundi 17 mai" (current year), or "17 mai 2024" (past years).
 */
fun Long.toDayHeaderLabel(
    locale: Locale = Locale.getDefault(),
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val date = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
    return when {
        date == today -> "Aujourd'hui"
        date == today.minusDays(1) -> "Hier"
        date.year == today.year ->
            // "lundi 17 mai"
            date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        else ->
            DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(this))
    }
}
