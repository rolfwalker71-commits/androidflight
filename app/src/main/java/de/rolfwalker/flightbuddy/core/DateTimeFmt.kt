package de.rolfwalker.flightbuddy.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * User-visible date/time. Numeric European pattern for DE and EN.
 * API request dates stay ISO (`LocalDate.toString()` = `yyyy-MM-dd`).
 */
object DateTimeFmt {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** Phone local zone — all user-visible clocks use this, not airport TZ. */
    fun deviceZone(): ZoneId = ZoneId.systemDefault()

    /** Airport IANA zone, or UTC when missing/invalid. Not for on-screen clocks. */
    fun airportZone(iana: String?): ZoneId =
        iana?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")

    fun date(day: LocalDate): String = DATE.format(day)

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Short weekday + date, e.g. `Di., 8. Sept.` / `Tue, 8 Sept`. */
    fun weekdayDate(ms: Long, zone: ZoneId = deviceZone(), language: String = "de"): String {
        val locale = if (language.startsWith("en")) Locale.US else Locale.GERMAN
        return DateTimeFormatter.ofPattern("EEE, d. MMM", locale)
            .format(Instant.ofEpochMilli(ms).atZone(zone))
    }

    fun time(ms: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (ms == null) return "—"
        return TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
    }

    fun dateTime(ms: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (ms == null) return "—"
        return DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
    }

    /**
     * Numeric offset for [zone] right now, e.g. `(UTC+2)`, `(UTC-4)`, `(UTC+5:30)`.
     * Uses [ZonedDateTime.now] so DST is the phone’s current offset, not a hardcoded zone.
     */
    fun offsetLabel(zone: ZoneId = deviceZone()): String {
        val total = ZonedDateTime.now(zone).offset.totalSeconds
        val sign = if (total >= 0) '+' else '-'
        val abs = kotlin.math.abs(total)
        val hours = abs / 3600
        val minutes = (abs % 3600) / 60
        val body = if (minutes == 0) "UTC$sign$hours" else "UTC$sign$hours:%02d".format(minutes)
        return "($body)"
    }

    /**
     * Short zone label for [zone] at [ms]: MESZ / MEZ / CEST / CET, or `UTC+2`.
     * Shows `UTC` only when that zone’s offset is actually zero.
     */
    fun timeZoneName(ms: Long?, zone: ZoneId, language: String = "de"): String? {
        if (ms == null) return null
        val instant = Instant.ofEpochMilli(ms)
        val offset = zone.rules.getOffset(instant)
        val locale = if (language.startsWith("en")) Locale.US else Locale.GERMAN
        val short = instant.atZone(zone).format(DateTimeFormatter.ofPattern("z", locale))
        if (short.isNotBlank() && short != "Z" && !short.startsWith("GMT")) return short
        val total = offset.totalSeconds
        if (total == 0) return "UTC"
        val hours = total / 3600
        val minutes = kotlin.math.abs(total % 3600) / 60
        return if (minutes == 0) "UTC%+d".format(hours) else "UTC%+d:%02d".format(hours, minutes)
    }

    fun clockWithZone(ms: Long?, zone: ZoneId = deviceZone()): String {
        if (ms == null) return "—"
        return "${time(ms, zone)} ${offsetLabel(zone)}"
    }
}
