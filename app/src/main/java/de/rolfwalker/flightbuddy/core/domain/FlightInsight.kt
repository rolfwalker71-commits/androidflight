package de.rolfwalker.flightbuddy.core.domain

import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

data class TimelineEvent(
    val id: String,
    val at: Long?,
    val done: Boolean,
)

fun FlightEntity.depZone(): ZoneId = DateTimeFmt.zoneOrDevice(fromTimezone)
fun FlightEntity.arrZone(): ZoneId = DateTimeFmt.zoneOrDevice(toTimezone)

fun FlightEntity.zonesDifferFromDevice(): Boolean {
    val device = DateTimeFmt.deviceZone()
    return (fromTimezone != null && depZone() != device) || (toTimezone != null && arrZone() != device)
}

fun airlineNameForIcao(icao: String?): String? {
    val key = icao?.trim()?.uppercase().orEmpty()
    if (key.isBlank()) return null
    return AirlineCatalog.lookup(key)?.name ?: key
}

fun wetLeaseLine(paintedAs: String?, operatingAs: String?): String? {
    val painted = paintedAs?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    val operating = operatingAs?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    if (painted == null || operating == null || painted == operating) return null
    val paintedName = airlineNameForIcao(painted)
    val operatingName = airlineNameForIcao(operating)
    if (paintedName == operatingName) return null
    return operatingName
}

fun parseTimeline(json: String?): List<TimelineEvent> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TimelineEvent(
                id = o.optString("id"),
                at = o.optLong("at").takeIf { o.has("at") && !o.isNull("at") && it > 0L },
                done = o.optBoolean("done", o.has("at") && !o.isNull("at")),
            )
        }
    }.getOrDefault(emptyList())
}

fun encodeTimeline(events: List<TimelineEvent>): String {
    val arr = JSONArray()
    for (e in events) {
        arr.put(
            JSONObject().put("id", e.id).apply {
                if (e.at != null) put("at", e.at) else put("at", JSONObject.NULL)
                put("done", e.done)
            },
        )
    }
    return arr.toString()
}

fun buildTimeline(
    firstSeen: Long?,
    takeoff: Long?,
    landed: Long?,
    lastSeen: Long?,
    now: Long,
    extra: List<TimelineEvent> = emptyList(),
): List<TimelineEvent> {
    val byId = linkedMapOf<String, TimelineEvent>()
    fun put(id: String, at: Long?) {
        byId[id] = TimelineEvent(id, at, at != null && at <= now)
    }
    put("gate_out", firstSeen)
    put("takeoff", takeoff)
    val cruiseAt = if (takeoff != null && landed != null && landed > takeoff) {
        takeoff + ((landed - takeoff) * 0.35).toLong()
    } else takeoff?.let { it + 15 * 60_000L }
    put("cruise", cruiseAt)
    val descentAt = landed?.let { it - 20 * 60_000L } ?: extra.find { it.id == "descent" }?.at
    put("descent", descentAt)
    put("landed", landed)
    put("gate_in", lastSeen)
    for (e in extra) {
        val prev = byId[e.id]
        if (prev == null || (e.at != null && prev.at == null)) byId[e.id] = e
    }
    return listOf("gate_out", "takeoff", "cruise", "descent", "landed", "gate_in").mapNotNull { byId[it] }
}

fun taxiMinutes(from: Long?, to: Long?): Int? {
    if (from == null || to == null || to <= from) return null
    return ((to - from) / 60_000L).toInt().takeIf { it in 1..180 }
}

fun medianInt(values: List<Int>): Int? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
