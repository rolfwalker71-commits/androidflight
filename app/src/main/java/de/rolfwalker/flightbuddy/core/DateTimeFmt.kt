package de.rolfwalker.flightbuddy.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * User-visible date/time. Numeric European pattern for DE and EN.
 * API request dates stay ISO (`LocalDate.toString()` = `yyyy-MM-dd`).
 */
object DateTimeFmt {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** Airport IANA zone, or UTC when missing/invalid — same as FlightBuddy PWA. */
    fun airportZone(iana: String?): ZoneId =
        iana?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")

    fun date(day: LocalDate): String = DATE.format(day)

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun time(ms: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (ms == null) return "—"
        return TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
    }

    fun dateTime(ms: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (ms == null) return "—"
        return DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
    }
}
