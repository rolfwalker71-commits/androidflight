package de.rolfwalker.flightbuddy.core.network

import de.rolfwalker.flightbuddy.core.data.db.ApiLogDao
import de.rolfwalker.flightbuddy.core.data.db.ApiLogEntity
import de.rolfwalker.flightbuddy.core.data.prefs.ApiKeys
import de.rolfwalker.flightbuddy.core.data.prefs.INVALID_API_CREDENTIAL_CHARS
import de.rolfwalker.flightbuddy.core.data.prefs.isInvalidApiCredentialException
import de.rolfwalker.flightbuddy.core.data.prefs.redactProviderError
import de.rolfwalker.flightbuddy.core.data.prefs.sanitizeApiCredential
import de.rolfwalker.flightbuddy.core.data.prefs.sanitized
import de.rolfwalker.flightbuddy.core.domain.callsignMatches
import de.rolfwalker.flightbuddy.core.domain.callsignPrefix
import de.rolfwalker.flightbuddy.core.domain.compactCallsign
import de.rolfwalker.flightbuddy.core.domain.mapProviderStatus
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.domain.paddedIataFlightNumbers
import de.rolfwalker.flightbuddy.core.domain.stripFlightZeros
import de.rolfwalker.flightbuddy.core.model.AircraftPhoto
import de.rolfwalker.flightbuddy.core.model.FlightSearchResult
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.SearchReason
import android.os.NetworkOnMainThreadException
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

data class LiveFix(
    val lat: Double,
    val lon: Double,
    val altitudeFt: Double? = null,
    val velocityKts: Double? = null,
    val heading: Double? = null,
    val verticalRateFpm: Double? = null,
    val onGround: Boolean = false,
    val icao24: String? = null,
    val callsign: String? = null,
    val squawk: String? = null,
    val source: String,
    val observedAt: Long = System.currentTimeMillis(),
    val eta: Long? = null,
    val aircraftType: String? = null,
    val registration: String? = null,
    val paintedAs: String? = null,
    val operatingAs: String? = null,
    val origIata: String? = null,
    val destIata: String? = null,
    val fr24Id: String? = null,
)

data class Fr24Summary(
    val fr24Id: String? = null,
    val flight: String? = null,
    val callsign: String? = null,
    val reg: String? = null,
    val type: String? = null,
    val paintedAs: String? = null,
    val operatingAs: String? = null,
    val origIata: String? = null,
    val destIata: String? = null,
    val destIataActual: String? = null,
    val takeoffAt: Long? = null,
    val landedAt: Long? = null,
    val firstSeen: Long? = null,
    val lastSeen: Long? = null,
    val runwayTakeoff: String? = null,
    val runwayLanded: String? = null,
    val flightTimeSec: Int? = null,
    val actualDistanceKm: Double? = null,
    val circleDistanceKm: Double? = null,
    val ended: Boolean = false,
)

data class AirportInfo(
    val iata: String?,
    val icao: String?,
    val name: String?,
    val city: String?,
    val country: String?,
    val timezone: String?,
    val lat: Double?,
    val lon: Double?,
    val elevationFt: Int? = null,
)

data class AirportWeather(
    val metar: String?,
    val taf: String? = null,
)

data class AirportDelayIndex(
    val delayMinutes: Int?,
)

data class AircraftInfo(
    val registration: String?,
    val type: String? = null,
    val operator: String? = null,
    val ageYears: Int? = null,
)

data class HistoricOpenSkyLeg(
    val icao24: String,
    val callsign: String?,
    val fromIcao: String?,
    val toIcao: String?,
    val firstSeen: Long?,
    val lastSeen: Long?,
)

data class AeroLookup(
    val flights: List<FlightSearchResult>,
    val reason: SearchReason,
    val httpStatus: Int? = null,
    val detail: String? = null,
)

data class TrafficState(
    val icao24: String,
    val callsign: String?,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Double?,
    val velocityKts: Double?,
    val heading: Double?,
    val verticalRateFpm: Double? = null,
    val onGround: Boolean,
    val squawk: String?,
    val country: String?,
    val airlineIata: String? = null,
    val airlineName: String? = null,
    val observedAt: Long = 0L,
)

class ProviderClients(
    private val logs: ApiLogDao,
) {
    private val aeroLogTag = "FlightBuddy/Aero"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private var openSkyToken: String? = null
    private var openSkyTokenExp = 0L
    private val lastOpenSky = AtomicLong(0)
    private val lastOpenSkyBbox = AtomicLong(0)
    private val lastFr24 = AtomicLong(0)
    private val lastAero = AtomicLong(0)
    @Volatile var lastOpenSkyError: String? = null
    @Volatile var lastAeroError: String? = null
    @Volatile var lastFr24Error: String? = null
    @Volatile var lastOpenSkyRemaining: Int? = null
    @Volatile var lastAeroRemaining: Int? = null
    @Volatile var lastFr24Remaining: Int? = null

    private suspend fun log(provider: String, endpoint: String, code: Int?, ok: Boolean, error: String?, remaining: Int?) {
        logs.insert(
            ApiLogEntity(
                provider = provider,
                endpoint = endpoint,
                statusCode = code,
                ok = ok,
                error = redactProviderError(error)?.take(400),
                remaining = remaining,
                at = System.currentTimeMillis(),
            ),
        )
    }

    private fun parseTime(raw: Any?): Long? {
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is Number) {
            val n = raw.toLong()
            if (n <= 0L) return null
            return if (n < 10_000_000_000L) n * 1000L else n
        }
        val s = when (raw) {
            is JSONObject -> raw.optString("utc").ifBlank { raw.optString("local") }
            else -> raw.toString()
        }.trim()
        if (s.isBlank() || s == "null") return null
        val spaced = Regex("""^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})(?::(\d{2}))?(Z|[+-]\d{2}:?\d{2})?$""").find(s)
        val iso = if (spaced != null) {
            val sec = spaced.groupValues[3].ifBlank { "00" }
            val off = spaced.groupValues[4].ifBlank { "Z" }.let { if (it.length == 5) "${it.substring(0, 3)}:${it.substring(3)}" else it }
            "${spaced.groupValues[1]}T${spaced.groupValues[2]}:$sec$off"
        } else s
        return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(iso).toEpochMilli() }
            .getOrNull()
    }

    /** Aero movement times: `runwayTime` is takeoff/landing; `actualTime` is legacy off-block. */
    private fun aeroTime(movement: JSONObject?, vararg keys: String): Long? {
        if (movement == null) return null
        for (key in keys) {
            parseTime(movement.opt(key))?.let { return it }
            parseTime(movement.opt("${key}Utc"))?.let { return it }
        }
        return null
    }

    private fun finite(vararg values: Any?): Double? {
        for (v in values) {
            val n = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (n != null && n.isFinite()) return n
        }
        return null
    }

    private fun airportTimeZone(airport: JSONObject?): String? {
        if (airport == null) return null
        val direct = airport.optString("timeZone").ifBlank { airport.optString("timezone") }
        if (direct.isNotBlank() && !direct.startsWith("{")) return direct
        val nested = airport.optJSONObject("timeZone") ?: airport.optJSONObject("timezone")
        return nested?.optString("name")?.ifBlank { nested.optString("id") }?.ifBlank { null }
    }

    private fun airportLocation(airport: JSONObject?): Pair<Double?, Double?> {
        val loc = airport?.optJSONObject("location") ?: airport
        val lat = loc?.let { finite(it.opt("lat"), it.opt("latitude")) }
        val lon = loc?.let { finite(it.opt("lon"), it.opt("longitude"), it.opt("lng")) }
        return lat to lon
    }

    private fun codeshareLine(obj: JSONObject): String? {
        val arr = obj.optJSONArray("codeshares") ?: obj.optJSONArray("codeShares") ?: return null
        val parts = (0 until arr.length()).mapNotNull { i ->
            val row = arr.optJSONObject(i) ?: return@mapNotNull arr.optString(i).ifBlank { null }
            row.optString("number").ifBlank { row.optString("code") }.ifBlank { null }
        }.filter { it.isNotBlank() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun mapAero(obj: JSONObject): FlightSearchResult {
        val dep = obj.optJSONObject("departure")
        val arr = obj.optJSONObject("arrival")
        val airline = obj.optJSONObject("airline")
        val aircraft = obj.optJSONObject("aircraft")
        val loc = obj.optJSONObject("location") ?: obj.optJSONObject("position")
        val depAirport = dep?.optJSONObject("airport")
        val arrAirport = arr?.optJSONObject("airport")
        val (depLat, depLon) = airportLocation(depAirport)
        val (arrLat, arrLon) = airportLocation(arrAirport)
        val statusRaw = obj.opt("status")?.let { s ->
            when (s) {
                is JSONObject -> s.optString("text").ifBlank { s.optString("name") }
                else -> s.toString()
            }
        }
        return FlightSearchResult(
            flightNumber = obj.optString("number").replace(Regex("\\s+"), ""),
            airlineName = airline?.optString("name")?.ifBlank { null },
            airlineIata = airline?.optString("iata")?.ifBlank { null },
            airlineIcao = airline?.optString("icao")?.ifBlank { null },
            fromIata = depAirport?.optString("iata")?.ifBlank { null },
            toIata = arrAirport?.optString("iata")?.ifBlank { null },
            fromCity = (depAirport?.optString("municipalityName") ?: depAirport?.optString("name"))?.ifBlank { null },
            toCity = (arrAirport?.optString("municipalityName") ?: arrAirport?.optString("name"))?.ifBlank { null },
            fromTimezone = airportTimeZone(depAirport),
            toTimezone = airportTimeZone(arrAirport),
            fromLat = depLat,
            fromLon = depLon,
            toLat = arrLat,
            toLon = arrLon,
            fromCountry = depAirport?.optString("countryCode")?.ifBlank { null },
            toCountry = arrAirport?.optString("countryCode")?.ifBlank { null },
            scheduledDep = aeroTime(dep, "scheduledTime"),
            scheduledArr = aeroTime(arr, "scheduledTime"),
            estimatedDep = aeroTime(dep, "revisedTime", "predictedTime"),
            estimatedArr = aeroTime(arr, "revisedTime", "predictedTime"),
            actualDep = aeroTime(dep, "runwayTime", "actualTime"),
            actualArr = aeroTime(arr, "runwayTime", "actualTime"),
            runwayDepAt = aeroTime(dep, "runwayTime"),
            runwayArrAt = aeroTime(arr, "runwayTime"),
            status = mapProviderStatus(statusRaw),
            gate = dep?.optString("gate")?.ifBlank { null },
            terminal = dep?.optString("terminal")?.ifBlank { null },
            arrivalGate = arr?.optString("gate")?.ifBlank { null },
            arrivalTerminal = arr?.optString("terminal")?.ifBlank { null },
            baggageBelt = listOf("baggageBelt", "baggage", "luggageBelt")
                .firstNotNullOfOrNull { key -> arr?.optString(key)?.ifBlank { null } },
            checkInDesk = dep?.optString("checkInDesk")?.ifBlank { dep?.optString("checkinDesk") }?.ifBlank { null },
            aircraftType = aircraft?.optString("model")?.ifBlank { aircraft?.optString("modelCode") }?.ifBlank { null },
            registration = aircraft?.optString("reg")?.ifBlank { null },
            icao24 = (aircraft?.optString("modeS") ?: aircraft?.optString("icao24"))?.ifBlank { null }?.lowercase(),
            callsign = (obj.optString("callSign").ifBlank { obj.optString("callsign") }).ifBlank { null },
            delayMinutes = dep?.optJSONObject("delay")?.optInt("minutes")?.takeIf { it != 0 },
            arrivalDelayMinutes = arr?.optJSONObject("delay")?.optInt("minutes")?.takeIf { it != 0 },
            codeshares = codeshareLine(obj),
            isCargo = obj.optBoolean("isCargo", false).takeIf { obj.has("isCargo") },
            liveLat = loc?.let { finite(it.opt("lat"), it.opt("latitude")) },
            liveLon = loc?.let { finite(it.opt("lon"), it.opt("longitude")) },
            liveAltitudeFt = aeroDistanceFt(loc),
            liveVelocityKts = aeroSpeedKt(loc),
            liveHeading = aeroTrackDeg(loc),
            liveVerticalRateFpm = loc?.let {
                finite(it.opt("vsiFpm"), it.opt("verticalSpeedFpm"), it.opt("verticalRate"))
            },
            source = "aerodatabox",
        )
    }

    private fun aeroNestedMeasure(obj: JSONObject?, vararg keys: String): Double? {
        if (obj == null) return null
        for (key in keys) {
            finite(obj.opt(key))?.let { return it }
            val nested = obj.optJSONObject(key) ?: continue
            finite(
                nested.opt("feet"), nested.opt("ft"),
                nested.opt("kt"), nested.opt("kts"),
                nested.opt("deg"), nested.opt("value"),
            )?.let { return it }
        }
        return null
    }

    private fun aeroDistanceFt(loc: JSONObject?): Double? =
        aeroNestedMeasure(loc, "altitude", "pressureAltitude", "altFt", "pressureAltFt")

    private fun aeroSpeedKt(loc: JSONObject?): Double? =
        aeroNestedMeasure(loc, "groundSpeed", "gsKt")

    private fun aeroTrackDeg(loc: JSONObject?): Double? =
        aeroNestedMeasure(loc, "trueTrack", "trackDeg", "track")

    private fun classifyAero(status: Int, body: String): SearchReason {
        val msg = body.lowercase()
        val invalidKey = listOf(
            "invalid_api_key",
            "invalid api key",
            "no valid api key",
            "unauthorized",
            "invalid x-api-market-key",
            "invalid x-magicapi-key",
        ).any { msg.contains(it) } ||
            (msg.contains("invalid") && msg.contains("key")) ||
            (msg.contains("missing") && msg.contains("key"))
        val notSub = listOf(
            "not subscribed",
            "subscription_not_found",
            "subscription not found",
            "not subscribed to this api",
            "no subscription",
            "exceed the maximum",
        ).any { msg.contains(it) } ||
            (msg.contains("subscription") && (msg.contains("inactive") || msg.contains("required")))
        if (status == 429) {
            return if (listOf("monthly", "quota", "api units", "api-units", "plan limit").any { msg.contains(it) }) {
                SearchReason.MONTHLY_QUOTA
            } else SearchReason.RATE_LIMITED
        }
        if (status == 400 && invalidKey) return SearchReason.NOT_SUBSCRIBED
        if (status == 401 || invalidKey) return SearchReason.NOT_SUBSCRIBED
        if (status == 403 && (notSub || invalidKey || msg.contains("forbidden"))) {
            return SearchReason.NOT_SUBSCRIBED
        }
        if (status == 404) {
            return if (notSub) SearchReason.NOT_SUBSCRIBED else SearchReason.EMPTY
        }
        return SearchReason.HTTP_ERROR
    }

    private fun httpDetail(status: Int, body: String, fallback: String? = null): String {
        val snippet = body.replace(Regex("\\s+"), " ").trim().take(160)
        val tail = snippet.ifBlank { fallback?.trim().orEmpty() }.ifBlank { "no body" }
        return "HTTP $status: $tail"
    }

    private fun classifyAeroException(e: Exception): Pair<SearchReason, String> {
        val raw = redactProviderError(e.message) ?: e.javaClass.simpleName
        return when {
            isInvalidApiCredentialException(e) ->
                SearchReason.INVALID_API_KEY to INVALID_API_CREDENTIAL_CHARS
            e is SocketTimeoutException || e is InterruptedIOException ->
                SearchReason.TIMEOUT to "timeout: $raw"
            e is UnknownHostException ->
                SearchReason.UNKNOWN_HOST to "unknown host: ${e.message ?: raw}"
            e is SSLException || e.cause is SSLException ->
                SearchReason.SSL_ERROR to "ssl: $raw"
            e is ConnectException ->
                SearchReason.NETWORK_ERROR to "connect: $raw"
            e is NetworkOnMainThreadException ->
                SearchReason.NETWORK_ERROR to "main-thread network"
            e is JSONException ->
                SearchReason.HTTP_ERROR to "invalid JSON: $raw"
            e is IllegalArgumentException && raw.contains("url", ignoreCase = true) ->
                SearchReason.BAD_URL to "invalid url: $raw"
            else -> SearchReason.NETWORK_ERROR to "${e.javaClass.simpleName}: $raw"
        }
    }

    private fun aeroHeaders(key: String, endpoint: AeroEndpoint): Map<String, String> {
        val cleanKey = sanitizeApiCredential(key)
        val cleanHost = sanitizeApiCredential(endpoint.aeroHost)
        val common = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "FlightBuddy/1.0 (Android)",
        )
        return if (endpoint.marketplace == "rapidapi") {
            common + mapOf(
                "X-RapidAPI-Key" to cleanKey,
                "X-RapidAPI-Host" to cleanHost.ifBlank { "aerodatabox.p.rapidapi.com" },
            )
        } else {
            common + mapOf(
                "x-api-market-key" to cleanKey,
                "x-magicapi-key" to cleanKey,
            )
        }
    }

    suspend fun searchAeroNumber(keys: ApiKeys, flightNumber: String, date: LocalDate, user: Boolean): AeroLookup {
        if (!keys.hasAeroDataBox()) return AeroLookup(emptyList(), SearchReason.UNCONFIGURED)
        val now = System.currentTimeMillis()
        if (now - lastAero.get() < 1_200) {
            if (!user) return AeroLookup(emptyList(), SearchReason.RATE_LIMITED)
            delay((1_250 - (now - lastAero.get())).coerceAtLeast(50))
        }
        lastAero.set(System.currentTimeMillis())
        val number = normalizeFlightNumber(flightNumber)
        // LocalDate.toString() is ISO yyyy-MM-dd — same path as the PWA.
        val day = date.toString()
        val from = if (user) date.minusDays(1).toString() else day
        val to = if (user) date.plusDays(1).toString() else day
        val rangePath = if (from == to) {
            "/flights/number/${encode(number)}/$day?dateLocalRole=Both&withLocation=true"
        } else {
            "/flights/number/${encode(number)}/$from/$to?dateLocalRole=Both&withLocation=true"
        }
        var lookup = fetchAeroList(keys, rangePath)
        if (user && lookup.httpStatus == 429) {
            delay(1_250)
            lastAero.set(System.currentTimeMillis())
            lookup = fetchAeroList(keys, rangePath)
        }
        // Some BASIC plans reject the ±1 day range; fall back to the selected day (PWA parity).
        if (user && from != to && lookup.httpStatus in setOf(403, 404)) {
            delay(1_200)
            lastAero.set(System.currentTimeMillis())
            val dayPath = "/flights/number/${encode(number)}/$day?dateLocalRole=Both&withLocation=true"
            lookup = fetchAeroList(keys, dayPath)
        }
        return lookup
    }

    suspend fun searchAeroRange(keys: ApiKeys, flightNumber: String, from: LocalDate, to: LocalDate): AeroLookup {
        if (!keys.hasAeroDataBox()) return AeroLookup(emptyList(), SearchReason.UNCONFIGURED)
        val number = normalizeFlightNumber(flightNumber)
        val path = "/flights/number/${encode(number)}/${from}/${to}?dateLocalRole=Both&withLocation=true"
        return fetchAeroList(keys, path)
    }

    suspend fun searchAeroAirportDepartures(keys: ApiKeys, fromIata: String, date: LocalDate): AeroLookup =
        searchAirportBoard(keys, fromIata, date, arrivals = false)

    suspend fun searchFr24Number(keys: ApiKeys, flightNumber: String, date: LocalDate): AeroLookup {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank() || !keys.fr24Enabled) return AeroLookup(emptyList(), SearchReason.UNCONFIGURED)
        val number = normalizeFlightNumber(flightNumber)
        val from = date.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val to = date.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val fromIso = Instant.ofEpochMilli(from).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val toIso = Instant.ofEpochMilli(to).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val hist = fetchFr24Summaries(keys, flights = number, fromIso = fromIso, toIso = toIso, limit = 20)
            .mapNotNull { summaryToSearchResult(it) }
        val flights = paddedIataFlightNumbers(number).take(2).joinToString(",")
        val live = if (flights.isNotBlank()) {
            fr24JsonArray(keys, "/api/live/flight-positions/full?flights=$flights&limit=15")
                .mapNotNull { mapFr24LiveSearch(it) }
        } else {
            emptyList()
        }
        val merged = linkedMapOf<String, FlightSearchResult>()
        for (row in hist + live) {
            val key = listOf(
                stripFlightZeros(compactCallsign(row.flightNumber)),
                row.fromIata.orEmpty(),
                row.toIata.orEmpty(),
            ).joinToString("|")
            val prev = merged[key]
            if (prev == null || (prev.liveLat == null && row.liveLat != null)) merged[key] = row
            else if (row.liveLat != null) {
                merged[key] = prev.copy(
                    liveLat = row.liveLat,
                    liveLon = row.liveLon,
                    liveAltitudeFt = row.liveAltitudeFt,
                    liveVelocityKts = row.liveVelocityKts,
                    liveHeading = row.liveHeading,
                    callsign = row.callsign ?: prev.callsign,
                    registration = prev.registration ?: row.registration,
                    icao24 = row.icao24 ?: prev.icao24,
                    estimatedArr = row.estimatedArr ?: prev.estimatedArr,
                    status = if (row.status == FlightStatus.EN_ROUTE) FlightStatus.EN_ROUTE else prev.status,
                    source = "fr24",
                )
            }
        }
        val rows = merged.values.toList()
        return AeroLookup(rows, if (rows.isEmpty()) SearchReason.EMPTY else SearchReason.OK)
    }

    suspend fun searchAirportBoard(
        keys: ApiKeys,
        iata: String,
        date: LocalDate,
        arrivals: Boolean,
    ): AeroLookup {
        val hasFr24 = sanitizeApiCredential(keys.fr24Token).isNotBlank() && keys.fr24Enabled
        val hasOpenSky = sanitizeApiCredential(keys.openSkyUsername).isNotBlank()
        if (!hasFr24 && !hasOpenSky) {
            return AeroLookup(emptyList(), SearchReason.UNCONFIGURED)
        }
        val code = iata.trim().uppercase()
        val info = fetchFr24Airport(keys, code)
        val zone = DateTimeFmt.airportZone(info?.timezone)
        val fromMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val merged = linkedMapOf<String, FlightSearchResult>()
        fun put(row: FlightSearchResult) {
            val key = listOf(
                stripFlightZeros(compactCallsign(row.flightNumber)),
                row.fromIata.orEmpty(),
                row.toIata.orEmpty(),
            ).joinToString("|")
            val prev = merged[key]
            if (prev == null || (prev.source == "opensky" && row.source == "fr24")) {
                merged[key] = row
            }
        }
        if (date == LocalDate.now()) {
            fetchFr24AirportLive(keys, code, arrivals).forEach(::put)
        }
        val fromIso = Instant.ofEpochMilli(fromMs).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val toIso = Instant.ofEpochMilli(toMs).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        fetchFr24Summaries(
            keys,
            airports = code,
            fromIso = fromIso,
            toIso = toIso,
            limit = 80,
        ).mapNotNull { summaryToBoardRow(it, code, arrivals) }.forEach(::put)
        val icao = info?.icao?.trim()?.uppercase()
        if (!icao.isNullOrBlank()) {
            fetchOpenSkyAirportFlights(keys, icao, fromMs, toMs, arrivals).forEach(::put)
        }
        val rows = merged.values.sortedBy { row ->
            if (arrivals) row.scheduledArr ?: row.estimatedArr ?: Long.MAX_VALUE
            else row.scheduledDep ?: row.estimatedDep ?: Long.MAX_VALUE
        }
        return AeroLookup(rows, if (rows.isEmpty()) SearchReason.EMPTY else SearchReason.OK)
    }

    suspend fun searchAeroAirportBoard(
        keys: ApiKeys,
        iata: String,
        date: LocalDate,
        arrivals: Boolean,
    ): AeroLookup {
        if (!keys.hasAeroDataBox()) return AeroLookup(emptyList(), SearchReason.UNCONFIGURED)
        val dir = if (arrivals) "Arrival" else "Departure"
        val code = encode(iata.trim().uppercase())
        val today = LocalDate.now()
        if (date == today) {
            return fetchAeroThrottled(
                keys,
                "/flights/airports/iata/$code?offsetMinutes=-120&durationMinutes=720" +
                    "&direction=$dir&withCancelled=true&withLeg=true",
            )
        }
        val day = date.toString()
        val windows = listOf(
            "${day}T00:00" to "${day}T12:00",
            "${day}T12:00" to "${day}T23:59",
        )
        val merged = linkedMapOf<String, FlightSearchResult>()
        var last = AeroLookup(emptyList(), SearchReason.EMPTY)
        for ((from, to) in windows) {
            val path = "/flights/airports/iata/$code/$from/$to?direction=$dir&withCancelled=true&withLeg=true"
            val lookup = fetchAeroThrottled(keys, path)
            last = lookup
            if (lookup.reason == SearchReason.OK) {
                for (row in lookup.flights) {
                    val key = listOf(
                        row.flightNumber,
                        row.fromIata,
                        row.toIata,
                        (if (arrivals) row.scheduledArr else row.scheduledDep)?.toString().orEmpty(),
                    ).joinToString("|")
                    merged.putIfAbsent(key, row)
                }
            } else if (lookup.reason in setOf(SearchReason.MONTHLY_QUOTA, SearchReason.RATE_LIMITED)) {
                return if (merged.isEmpty()) lookup else AeroLookup(merged.values.toList(), SearchReason.OK)
            }
        }
        return if (merged.isNotEmpty()) AeroLookup(merged.values.toList(), SearchReason.OK)
        else last
    }

    suspend fun fetchFr24AirportLive(
        keys: ApiKeys,
        iata: String,
        arrivals: Boolean,
    ): List<FlightSearchResult> {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank() || !keys.fr24Enabled) return emptyList()
        val code = iata.trim().uppercase()
        if (code.length !in 3..4) return emptyList()
        val dir = if (arrivals) "inbound" else "outbound"
        val rows = fr24JsonArray(
            keys,
            "/api/live/flight-positions/full?airports=$dir:$code&limit=200",
        )
        return rows.mapNotNull { row -> mapFr24BoardRow(row, arrivals) }
            .sortedBy { if (arrivals) it.estimatedArr ?: Long.MAX_VALUE else it.estimatedDep ?: Long.MAX_VALUE }
    }

    private fun mapFr24BoardRow(row: JSONObject, arrivals: Boolean): FlightSearchResult? {
        val number = compactCallsign(row.optString("flight")).ifBlank {
            compactCallsign(row.optString("callsign"))
        }
        if (number.isBlank()) return null
        val from = row.optString("orig_iata").ifBlank { null }
        val to = row.optString("dest_iata").ifBlank { null }
        val airborne = !row.optBoolean("on_ground", false) &&
            (finite(row.opt("alt"), row.opt("altitude")) ?: 0.0) > 50.0
        val status = when {
            airborne -> FlightStatus.EN_ROUTE
            arrivals -> FlightStatus.LANDED
            else -> FlightStatus.DEPARTED
        }
        val eta = parseTime(row.opt("eta"))
        return FlightSearchResult(
            flightNumber = number,
            airlineIata = Regex("^([A-Z]{2})\\d").find(number)?.groupValues?.get(1),
            airlineIcao = row.optString("painted_as").ifBlank { row.optString("operating_as") }.ifBlank { null },
            fromIata = from,
            toIata = to,
            estimatedDep = if (arrivals) null else eta,
            estimatedArr = if (arrivals) eta else null,
            status = status,
            aircraftType = row.optString("type").ifBlank { null },
            registration = row.optString("reg").ifBlank { null },
            icao24 = row.optString("hex").ifBlank { null }?.lowercase(),
            callsign = row.optString("callsign").ifBlank { null },
            liveLat = finite(row.opt("lat"), row.opt("latitude")),
            liveLon = finite(row.opt("lon"), row.opt("lng"), row.opt("longitude")),
            liveAltitudeFt = finite(row.opt("alt"), row.opt("altitude")),
            liveVelocityKts = finite(row.opt("gspeed"), row.opt("speed")),
            liveHeading = finite(row.opt("track"), row.opt("heading")),
            source = "fr24",
        )
    }

    private fun summaryToBoardRow(s: Fr24Summary, airport: String, arrivals: Boolean): FlightSearchResult? {
        val here = airport.uppercase()
        val dest = (s.destIataActual ?: s.destIata)?.uppercase()
        val orig = s.origIata?.uppercase()
        if (arrivals && dest != here) return null
        if (!arrivals && orig != here) return null
        val number = compactCallsign(s.flight).ifBlank { compactCallsign(s.callsign) }
        if (number.isBlank()) return null
        val status = when {
            s.ended || s.landedAt != null -> FlightStatus.LANDED
            s.takeoffAt != null -> FlightStatus.EN_ROUTE
            else -> FlightStatus.SCHEDULED
        }
        return FlightSearchResult(
            flightNumber = number,
            airlineIata = Regex("^([A-Z]{2})\\d").find(number)?.groupValues?.get(1),
            airlineIcao = s.paintedAs ?: s.operatingAs,
            fromIata = orig,
            toIata = dest,
            scheduledDep = s.takeoffAt ?: s.firstSeen,
            scheduledArr = s.landedAt ?: s.lastSeen,
            status = status,
            aircraftType = s.type,
            registration = s.reg,
            callsign = s.callsign,
            source = "fr24",
        )
    }

    private fun summaryToSearchResult(s: Fr24Summary): FlightSearchResult? {
        val number = compactCallsign(s.flight).ifBlank { compactCallsign(s.callsign) }
        if (number.isBlank()) return null
        val status = when {
            s.ended || s.landedAt != null -> FlightStatus.LANDED
            s.takeoffAt != null -> FlightStatus.EN_ROUTE
            else -> FlightStatus.SCHEDULED
        }
        return FlightSearchResult(
            flightNumber = number,
            airlineIata = Regex("^([A-Z]{2})\\d").find(number)?.groupValues?.get(1),
            airlineIcao = s.paintedAs ?: s.operatingAs,
            fromIata = s.origIata,
            toIata = s.destIataActual ?: s.destIata,
            scheduledDep = s.takeoffAt ?: s.firstSeen,
            scheduledArr = s.landedAt ?: s.lastSeen,
            actualDep = s.takeoffAt,
            actualArr = s.landedAt,
            status = status,
            aircraftType = s.type,
            registration = s.reg,
            callsign = s.callsign,
            source = "fr24",
        )
    }

    private fun mapFr24LiveSearch(row: JSONObject): FlightSearchResult? {
        val number = compactCallsign(row.optString("flight")).ifBlank {
            compactCallsign(row.optString("callsign"))
        }
        if (number.isBlank()) return null
        val airborne = !row.optBoolean("on_ground", false) &&
            (finite(row.opt("alt"), row.opt("altitude")) ?: 0.0) > 50.0
        return FlightSearchResult(
            flightNumber = number,
            airlineIata = Regex("^([A-Z]{2})\\d").find(number)?.groupValues?.get(1),
            airlineIcao = row.optString("painted_as").ifBlank { row.optString("operating_as") }.ifBlank { null },
            fromIata = row.optString("orig_iata").ifBlank { null },
            toIata = row.optString("dest_iata").ifBlank { null },
            estimatedArr = parseTime(row.opt("eta")),
            status = when {
                airborne -> FlightStatus.EN_ROUTE
                row.optBoolean("on_ground", false) -> FlightStatus.DEPARTED
                else -> FlightStatus.SCHEDULED
            },
            aircraftType = row.optString("type").ifBlank { null },
            registration = row.optString("reg").ifBlank { null },
            icao24 = row.optString("hex").ifBlank { null }?.lowercase(),
            callsign = row.optString("callsign").ifBlank { null },
            liveLat = finite(row.opt("lat"), row.opt("latitude")),
            liveLon = finite(row.opt("lon"), row.opt("lng"), row.opt("longitude")),
            liveAltitudeFt = finite(row.opt("alt"), row.opt("altitude")),
            liveVelocityKts = finite(row.opt("gspeed"), row.opt("speed")),
            liveHeading = finite(row.opt("track"), row.opt("heading")),
            liveVerticalRateFpm = finite(row.opt("vspeed"), row.opt("vrate")),
            source = "fr24",
        )
    }

    private suspend fun fetchAeroThrottled(keys: ApiKeys, path: String): AeroLookup {
        val wait = 1_250L - (System.currentTimeMillis() - lastAero.get())
        if (wait > 0) delay(wait)
        lastAero.set(System.currentTimeMillis())
        return fetchAeroList(keys, path)
    }

    suspend fun lookupAeroLive(keys: ApiKeys, flightNumber: String, date: LocalDate): FlightSearchResult? {
        if (!keys.hasAeroDataBox()) return null
        val path = "/flights/number/${encode(normalizeFlightNumber(flightNumber))}/${date}?dateLocalRole=Both&withLocation=true"
        return fetchAeroList(keys, path).flights.firstOrNull()
    }

    private suspend fun fetchAeroList(keys: ApiKeys, path: String): AeroLookup = withContext(Dispatchers.IO) {
        val clean = keys.sanitized()
        val endpoint = clean.aeroEndpointOrNull()
            ?: return@withContext AeroLookup(emptyList(), SearchReason.UNCONFIGURED)
        val url = try {
            joinAeroUrl(endpoint.aeroBaseUrl, path)
        } catch (e: Exception) {
            val detail = "invalid url: ${redactProviderError(e.message) ?: e.javaClass.simpleName}"
            lastAeroError = detail
            log("aerodatabox", path, null, false, detail, null)
            return@withContext AeroLookup(emptyList(), SearchReason.BAD_URL, detail = detail)
        }
        // URL only — never log the API key (it lives in headers).
        Log.i(aeroLogTag, "GET $url")
        val req = try {
            Request.Builder().url(url).apply {
                aeroHeaders(clean.aeroKey, endpoint).forEach { addHeader(it.key, it.value) }
            }.build()
        } catch (e: Exception) {
            if (isInvalidApiCredentialException(e)) {
                lastAeroError = INVALID_API_CREDENTIAL_CHARS
                log("aerodatabox", url, null, false, lastAeroError, null)
                return@withContext AeroLookup(emptyList(), SearchReason.INVALID_API_KEY, detail = lastAeroError)
            }
            val detail = "invalid request: ${redactProviderError(e.message) ?: e.javaClass.simpleName}"
            lastAeroError = detail
            log("aerodatabox", url, null, false, detail, null)
            return@withContext AeroLookup(emptyList(), SearchReason.BAD_URL, detail = detail)
        }
        try {
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                val remaining = res.header("x-ratelimit-remaining")?.toIntOrNull()
                    ?: res.header("X-RateLimit-Remaining")?.toIntOrNull()
                lastAeroRemaining = remaining
                if (res.code == 204) {
                    lastAeroError = null
                    log("aerodatabox", url, res.code, true, null, remaining)
                    return@withContext AeroLookup(emptyList(), SearchReason.EMPTY, res.code)
                }
                if (!res.isSuccessful) {
                    val detail = httpDetail(res.code, body, res.message)
                    lastAeroError = redactProviderError(detail)
                    log("aerodatabox", url, res.code, false, lastAeroError, remaining)
                    val reason = classifyAero(res.code, body).let { classified ->
                        if (classified == SearchReason.RATE_LIMITED && remaining == 0) {
                            SearchReason.MONTHLY_QUOTA
                        } else {
                            classified
                        }
                    }
                    return@withContext AeroLookup(
                        emptyList(),
                        reason,
                        res.code,
                        lastAeroError,
                    )
                }
                lastAeroError = null
                log("aerodatabox", url, res.code, true, null, remaining)
                val rows = try {
                    rowsFrom(body).map { mapAero(it) }.filter { it.flightNumber.isNotBlank() || it.fromIata != null }
                } catch (e: JSONException) {
                    val detail = httpDetail(res.code, body, "invalid JSON")
                    lastAeroError = detail
                    log("aerodatabox", url, res.code, false, detail, remaining)
                    return@withContext AeroLookup(emptyList(), SearchReason.HTTP_ERROR, res.code, detail)
                }
                AeroLookup(rows, if (rows.isEmpty()) SearchReason.EMPTY else SearchReason.OK, res.code)
            }
        } catch (e: Exception) {
            val (reason, detail) = classifyAeroException(e)
            lastAeroError = detail
            log("aerodatabox", url, null, false, detail, null)
            AeroLookup(emptyList(), reason, detail = detail)
        }
    }

    private fun rowsFrom(text: String): List<JSONObject> {
        if (text.isBlank()) return emptyList()
        val json: Any = if (text.trim().startsWith("[")) JSONArray(text) else JSONObject(text)
        return when (json) {
            is JSONArray -> (0 until json.length()).map { json.getJSONObject(it) }
            is JSONObject -> {
                val items = json.optJSONArray("departures") ?: json.optJSONArray("arrivals") ?: json.optJSONArray("items")
                when {
                    items != null -> (0 until items.length()).map { items.getJSONObject(it) }
                    json.has("number") || json.has("departure") -> listOf(json)
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private suspend fun openSkyBearer(keys: ApiKeys): String? {
        val user = sanitizeApiCredential(keys.openSkyUsername)
        val pass = sanitizeApiCredential(keys.openSkyPassword)
        if (user.isBlank() || pass.isBlank()) return null
        if (openSkyToken != null && System.currentTimeMillis() < openSkyTokenExp) return openSkyToken
        val body = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", user)
            .add("client_secret", pass)
            .build()
        val req = Request.Builder()
            .url("https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token")
            .post(body)
            .header("Accept", "application/json")
            .build()
        return try {
            withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    lastOpenSkyError = "token ${res.code}"
                    return@withContext null
                }
                val obj = JSONObject(text)
                openSkyToken = obj.optString("access_token").ifBlank { null }
                val ttl = (obj.optInt("expires_in", 1800) - 60).coerceAtLeast(60)
                openSkyTokenExp = System.currentTimeMillis() + ttl * 1000L
                openSkyToken
            }
            }
        } catch (e: Exception) {
            lastOpenSkyError = if (isInvalidApiCredentialException(e)) {
                INVALID_API_CREDENTIAL_CHARS
            } else redactProviderError(e.message)
            null
        }
    }

    suspend fun fetchOpenSky(
        keys: ApiKeys,
        icao24: String? = null,
        lamin: Double? = null,
        lamax: Double? = null,
        lomin: Double? = null,
        lomax: Double? = null,
        ignoreInterval: Boolean = false,
    ): List<TrafficState> {
        val hasIcao = !icao24.isNullOrBlank()
        val hasBox = lamin != null && lamax != null && lomin != null && lomax != null
        if (!hasIcao && !hasBox) return emptyList()
        val min = keys.openSkyMinIntervalMs.toLong().coerceAtLeast(90_000)
        val since = System.currentTimeMillis() - lastOpenSky.get()
        if (!ignoreInterval) {
            if (lastOpenSky.get() != 0L && since < min) return emptyList()
            lastOpenSky.set(System.currentTimeMillis())
        }
        val qs = buildString {
            append("https://opensky-network.org/api/states/all")
            val parts = mutableListOf<String>()
            if (!icao24.isNullOrBlank()) parts += "icao24=${icao24.lowercase()}"
            if (lamin != null && lamax != null && lomin != null && lomax != null) {
                parts += "lamin=$lamin&lamax=$lamax&lomin=$lomin&lomax=$lomax"
            }
            if (parts.isNotEmpty()) append("?").append(parts.joinToString("&"))
        }
        val token = openSkyBearer(keys)?.let { sanitizeApiCredential(it) }
        return try {
            val req = Request.Builder().url(qs).header("Accept", "application/json").apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                lastOpenSkyRemaining = res.header("X-Rate-Limit-Remaining")?.toIntOrNull()
                    ?: res.header("x-rate-limit-remaining")?.toIntOrNull()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    lastOpenSkyError = redactProviderError("${res.code} ${body.take(120)}")
                    log("opensky", qs, res.code, false, lastOpenSkyError, lastOpenSkyRemaining)
                    return@withContext emptyList()
                }
                lastOpenSkyError = null
                log("opensky", qs, res.code, true, null, lastOpenSkyRemaining)
                val states = JSONObject(body).optJSONArray("states") ?: return@withContext emptyList()
                (0 until states.length()).mapNotNull { i ->
                    val row = states.optJSONArray(i) ?: return@mapNotNull null
                    val lat = row.optDouble(6, Double.NaN)
                    val lon = row.optDouble(5, Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
                    TrafficState(
                        icao24 = row.optString(0).lowercase(),
                        callsign = row.optString(1).trim().ifBlank { null },
                        lat = lat,
                        lon = lon,
                        altitudeFt = row.optDouble(13, Double.NaN).takeIf { it.isFinite() }?.times(3.28084)
                            ?: row.optDouble(7, Double.NaN).takeIf { it.isFinite() }?.times(3.28084),
                        velocityKts = row.optDouble(9, Double.NaN).takeIf { it.isFinite() }?.div(0.514444),
                        heading = row.optDouble(10, Double.NaN).takeIf { it.isFinite() },
                        verticalRateFpm = row.optDouble(11, Double.NaN).takeIf { it.isFinite() }?.times(196.850394),
                        onGround = row.optBoolean(8, false),
                        squawk = row.optString(14).ifBlank { null },
                        country = row.optString(2).ifBlank { null },
                    )
                }
            }
            }
        } catch (e: Exception) {
            lastOpenSkyError = if (isInvalidApiCredentialException(e)) {
                INVALID_API_CREDENTIAL_CHARS
            } else redactProviderError(e.message)
            log("opensky", qs, null, false, lastOpenSkyError, null)
            emptyList()
        }
    }

    /**
     * Viewport traffic. Own interval so the tracker’s icao24 polls do not
     * silently swallow map updates (that used to return an empty list).
     * `null` means throttled — keep the last drawn aircraft.
     */
    suspend fun fetchOpenSkyBbox(
        keys: ApiKeys,
        lamin: Double,
        lamax: Double,
        lomin: Double,
        lomax: Double,
    ): List<TrafficState>? {
        val min = keys.openSkyMinIntervalMs.toLong().coerceAtLeast(90_000)
        val since = System.currentTimeMillis() - lastOpenSkyBbox.get()
        if (lastOpenSkyBbox.get() != 0L && since < min) return null
        lastOpenSkyBbox.set(System.currentTimeMillis())
        return fetchOpenSky(
            keys,
            lamin = lamin,
            lamax = lamax,
            lomin = lomin,
            lomax = lomax,
            ignoreInterval = true,
        )
    }

    suspend fun fetchFr24(
        keys: ApiKeys,
        flightNumber: String?,
        callsign: String?,
        icao24: String?,
        lat: Double?,
        lon: Double?,
        registration: String? = null,
        ignoreInterval: Boolean = false,
    ): LiveFix? {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank() || !keys.fr24Enabled) return null
        val configured = keys.fr24MinIntervalMs.toLong()
        val min = (if (configured >= 180_000L) 60_000L else configured).coerceAtLeast(45_000L)
        val since = System.currentTimeMillis() - lastFr24.get()
        if (!ignoreInterval && lastFr24.get() != 0L && since < min) return null
        lastFr24.set(System.currentTimeMillis())
        val flights = paddedIataFlightNumbers(flightNumber).take(2).joinToString(",").ifBlank { null }
        val signs = callsign?.split(',')
            ?.map { compactCallsign(it) }
            ?.filter { it.isNotBlank() && callsignPrefix(it)?.length == 3 }
            ?.distinct()
            ?.joinToString(",")
            ?.ifBlank { null }
        val reg = registration?.uppercase()?.replace(Regex("[\\s-]+"), "")?.ifBlank { null }
        val params = mutableListOf("limit=15")
        // FR24 ANDs filters. `flights=EK86&callsigns=UAE86` misses `UAE8T`.
        if (flights != null) {
            params += "flights=$flights"
        } else if (signs != null) {
            params += "callsigns=$signs"
        } else if (reg != null) {
            params += "registrations=$reg"
        }
        val path = when {
            flights != null || signs != null || reg != null ->
                "/api/live/flight-positions/full?${params.joinToString("&")}"
            icao24 != null && lat != null && lon != null -> {
                val n = (lat + 1.5).coerceAtMost(90.0)
                val s = (lat - 1.5).coerceAtLeast(-90.0)
                params += "bounds=$n,$s,${lon - 1.5},${lon + 1.5}"
                "/api/live/flight-positions/full?${params.joinToString("&")}"
            }
            else -> return null
        }
        return try {
            val req = Request.Builder()
                .url("https://fr24api.flightradar24.com$path")
                .header("Accept", "application/json")
                .header("Accept-Version", "v1")
                .header("Authorization", "Bearer $token")
                .build()
            withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                lastFr24Remaining = res.header("x-rate-limit-remaining")?.toIntOrNull()
                if (!res.isSuccessful) {
                    lastFr24Error = redactProviderError("${res.code} ${body.take(120)}")
                    log("fr24", path, res.code, false, lastFr24Error, lastFr24Remaining)
                    return@withContext null
                }
                lastFr24Error = null
                log("fr24", path, res.code, true, null, lastFr24Remaining)
                val root = if (body.trim().startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("data") ?: JSONArray()
                if (root.length() == 0) return@withContext null
                val rows = (0 until root.length()).map { root.getJSONObject(it) }
                val flightKeys = paddedIataFlightNumbers(flightNumber).toSet()
                val signSet = signs?.split(',')?.toSet().orEmpty()
                val row = rows.firstOrNull { obj ->
                    icao24 != null && obj.optString("hex").equals(icao24, true)
                } ?: rows.firstOrNull { obj ->
                    val f = compactCallsign(obj.optString("flight"))
                    val c = compactCallsign(obj.optString("callsign"))
                    (f.isNotEmpty() && flightKeys.any { it == f || stripFlightZeros(f) == stripFlightZeros(it) }) ||
                        callsignMatches(c, signSet)
                } ?: rows.firstOrNull()
                if (row == null) return@withContext null
                val rlat = row.optDouble("lat", Double.NaN)
                val rlon = row.optDouble("lon", Double.NaN)
                if (!rlat.isFinite() || !rlon.isFinite()) return@withContext null
                val alt = finite(row.opt("alt"), row.opt("altitude"), row.opt("alt_ft"))
                val speed = finite(row.opt("gspeed"), row.opt("speed"), row.opt("gs"))
                val track = finite(row.opt("track"), row.opt("heading"), row.opt("hdg"))
                LiveFix(
                    lat = rlat,
                    lon = rlon,
                    altitudeFt = alt,
                    velocityKts = speed,
                    heading = track,
                    verticalRateFpm = finite(row.opt("vspeed"), row.opt("vrate")),
                    onGround = row.optBoolean("on_ground", alt != null && alt <= 0),
                    icao24 = row.optString("hex").ifBlank { null }?.lowercase(),
                    callsign = row.optString("callsign").ifBlank { null },
                    squawk = row.opt("squawk")?.toString()?.padStart(4, '0')?.takeIf { it.length == 4 },
                    source = "fr24",
                    eta = parseTime(row.opt("eta")),
                    aircraftType = row.optString("type").ifBlank { null },
                    registration = row.optString("reg").ifBlank { null },
                    paintedAs = row.optString("painted_as").ifBlank { null },
                    operatingAs = row.optString("operating_as").ifBlank { null },
                    origIata = row.optString("orig_iata").ifBlank { null },
                    destIata = row.optString("dest_iata").ifBlank { null },
                    fr24Id = row.optString("fr24_id").ifBlank { null },
                )
            }
            }
        } catch (e: Exception) {
            lastFr24Error = if (isInvalidApiCredentialException(e)) {
                INVALID_API_CREDENTIAL_CHARS
            } else redactProviderError(e.message)
            log("fr24", path, null, false, lastFr24Error, null)
            null
        }
    }

    suspend fun planespottersPhoto(reg: String): AircraftPhoto? {
        val clean = reg.uppercase().replace(Regex("\\s+"), "")
        val req = Request.Builder()
            .url("https://api.planespotters.net/pub/photos/reg/$clean")
            .header("Accept", "application/json")
            .header("User-Agent", "FlightBuddy/1.0")
            .build()
        return try {
            withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val photos = JSONObject(res.body?.string().orEmpty()).optJSONArray("photos") ?: return@withContext null
                if (photos.length() == 0) return@withContext null
                val p = photos.getJSONObject(0)
                val thumb = p.optJSONObject("thumbnail_large") ?: p.optJSONObject("thumbnail")
                val src = thumb?.optString("src")?.ifBlank { null } ?: return@withContext null
                AircraftPhoto(
                    url = src,
                    webUrl = p.optString("link").ifBlank { null },
                    photographer = p.optString("photographer").ifBlank { null },
                    source = "Planespotters.net",
                )
            }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchFr24Summaries(
        keys: ApiKeys,
        flights: String? = null,
        registrations: String? = null,
        airports: String? = null,
        fromIso: String,
        toIso: String,
        limit: Int = 40,
    ): List<Fr24Summary> {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank() || !keys.fr24Enabled) return emptyList()
        val params = mutableListOf(
            "flight_datetime_from=$fromIso",
            "flight_datetime_to=$toIso",
            "limit=$limit",
            "sort=desc",
        )
        if (!flights.isNullOrBlank()) params += "flights=${flights.replace(Regex("[\\s-]+"), "")}"
        if (!registrations.isNullOrBlank()) params += "registrations=${registrations.replace(" ", "")}"
        if (!airports.isNullOrBlank()) params += "airports=${airports.replace(Regex("\\s+"), "")}"
        if (flights.isNullOrBlank() && registrations.isNullOrBlank() && airports.isNullOrBlank()) return emptyList()
        val path = "/api/flight-summary/full?${params.joinToString("&")}"
        return fr24JsonArray(keys, path).mapNotNull { mapFr24Summary(it) }
    }

    suspend fun fetchFr24Events(keys: ApiKeys, fr24Id: String): List<Pair<String, Long>> {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank() || !keys.fr24Enabled || fr24Id.isBlank()) return emptyList()
        val path = "/api/historic/flight-events/full?flight_ids=$fr24Id&event_types=all"
        val rows = fr24JsonArray(keys, path)
        val out = mutableListOf<Pair<String, Long>>()
        for (i in 0 until rows.size) {
            val obj = rows[i]
            val events = obj.optJSONArray("events") ?: continue
            for (j in 0 until events.length()) {
                val ev = events.optJSONObject(j) ?: continue
                val type = ev.optString("type").ifBlank { ev.optString("event_type") }
                val at = parseTime(ev.opt("event_timestamp") ?: ev.opt("timestamp")) ?: continue
                if (type.isNotBlank()) out += type to at
            }
        }
        return out
    }

    suspend fun fetchFr24Airport(keys: ApiKeys, code: String): AirportInfo? {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank() || !keys.fr24Enabled) return null
        val path = "/api/static/airports/${encode(code)}/full"
        val obj = fr24JsonObject(keys, path) ?: return null
        val tz = obj.optJSONObject("timezone")?.optString("name")
            ?: obj.optString("timezone").ifBlank { null }
        val loc = obj.optJSONObject("location")
        return AirportInfo(
            iata = obj.optString("iata").ifBlank { code.takeIf { it.length == 3 } },
            icao = obj.optString("icao").ifBlank { null },
            name = obj.optString("name").ifBlank { null },
            city = obj.optString("city").ifBlank { null },
            country = obj.optString("country")?.ifBlank { obj.optString("country_code") }?.ifBlank { null },
            timezone = tz,
            lat = loc?.let { finite(it.opt("lat"), it.opt("latitude")) } ?: finite(obj.opt("lat")),
            lon = loc?.let { finite(it.opt("lon"), it.opt("longitude")) } ?: finite(obj.opt("lon")),
            elevationFt = obj.optInt("elevation").takeIf { obj.has("elevation") && it != 0 }
                ?: finite(obj.opt("elevation"))?.toInt(),
        )
    }

    suspend fun fetchAeroAirport(keys: ApiKeys, iata: String): AirportInfo? {
        if (!keys.hasAeroDataBox() || iata.isBlank()) return null
        val obj = fetchAeroObject(keys, "/airports/iata/${encode(iata)}") ?: return null
        val loc = obj.optJSONObject("location")
        return AirportInfo(
            iata = obj.optString("iata").ifBlank { iata },
            icao = obj.optString("icao").ifBlank { null },
            name = obj.optString("name").ifBlank { obj.optString("shortName") }.ifBlank { null },
            city = obj.optString("municipalityName").ifBlank { null },
            country = obj.optString("countryCode").ifBlank { null },
            timezone = airportTimeZone(obj),
            lat = loc?.let { finite(it.opt("lat"), it.opt("latitude")) },
            lon = loc?.let { finite(it.opt("lon"), it.opt("longitude")) },
            elevationFt = finite(obj.opt("elevation"))?.toInt(),
        )
    }

    suspend fun fetchAeroWeather(keys: ApiKeys, iata: String, day: LocalDate): AirportWeather? {
        if (!keys.hasAeroDataBox() || iata.isBlank()) return null
        val path = "/airports/iata/${encode(iata)}/weather/${day}/${day}"
        val obj = fetchAeroObject(keys, path) ?: return null
        val first = obj.optJSONArray("weather")?.optJSONObject(0)
            ?: obj.optJSONArray("items")?.optJSONObject(0)
            ?: obj
        val metar = first.optString("metar").ifBlank { first.optString("raw") }
            .ifBlank { obj.optString("metar") }.ifBlank { null }
        val taf = first.optString("taf").ifBlank { obj.optString("taf") }.ifBlank { null }
        if (metar == null && taf == null) return null
        return AirportWeather(metar = metar, taf = taf)
    }

    suspend fun fetchAeroDelayIndex(keys: ApiKeys, iata: String, day: LocalDate): AirportDelayIndex? {
        if (!keys.hasAeroDataBox() || iata.isBlank()) return null
        val obj = fetchAeroObject(keys, "/airports/iata/${encode(iata)}/delays/${day}") ?: return null
        val current = obj.optJSONObject("current") ?: obj
        val minutes = listOf("averageDelay", "meanDelay", "delayMinutes", "delay")
            .firstNotNullOfOrNull { key ->
                current.optJSONObject(key)?.optInt("minutes")?.takeIf { it != 0 }
                    ?: current.optInt(key).takeIf { current.has(key) && it != 0 }
            }
        val index = current.optDouble("delayIndex", Double.NaN).takeIf { it.isFinite() }
        val fromIndex = index?.let { (it * 15).toInt() }
        val delay = minutes ?: fromIndex
        return delay?.let { AirportDelayIndex(it) }
    }

    suspend fun fetchAeroAircraft(keys: ApiKeys, reg: String): AircraftInfo? {
        if (!keys.hasAeroDataBox() || reg.isBlank()) return null
        val clean = reg.uppercase().replace(Regex("\\s+"), "")
        val obj = fetchAeroObject(keys, "/aircrafts/reg/${encode(clean)}") ?: return null
        val age = obj.optInt("ageYears").takeIf { obj.has("ageYears") && it > 0 }
            ?: obj.optJSONObject("age")?.optInt("years")?.takeIf { it > 0 }
        return AircraftInfo(
            registration = obj.optString("reg").ifBlank { clean },
            type = obj.optString("typeName").ifBlank { obj.optString("model") }.ifBlank { null },
            operator = obj.optJSONObject("airline")?.optString("name")?.ifBlank { obj.optString("operator") }?.ifBlank { null },
            ageYears = age,
        )
    }

    suspend fun fetchFr24HistoricTrack(
        keys: ApiKeys,
        fr24Id: String?,
        flightNumber: String?,
        aroundMs: Long?,
    ): List<de.rolfwalker.flightbuddy.core.model.LatLon> {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank() || !keys.fr24Enabled) return emptyList()
        if (!fr24Id.isNullOrBlank()) {
            val pts = positionsFromFr24(
                fr24JsonArray(keys, "/api/historic/flight-positions/full?flight_ids=${encode(fr24Id)}&limit=1500"),
            )
            if (pts.size >= 2) return pts
        }
        val flight = compactCallsign(flightNumber)
        if (flight.isNotBlank() && aroundMs != null && aroundMs > 0L) {
            val ts = aroundMs / 1000L
            return positionsFromFr24(
                fr24JsonArray(keys, "/api/historic/flight-positions/full?flights=$flight&timestamp=$ts&limit=800"),
            )
        }
        return emptyList()
    }

    suspend fun fetchOpenSkyAircraftFlights(
        keys: ApiKeys,
        icao24: String,
        fromMs: Long,
        toMs: Long,
    ): List<HistoricOpenSkyLeg> {
        val hex = icao24.lowercase().trim()
        if (hex.isBlank()) return emptyList()
        return fetchOpenSkyFlightList(
            keys,
            "https://opensky-network.org/api/flights/aircraft?icao24=$hex&begin=${unixRange(fromMs, toMs).first}&end=${unixRange(fromMs, toMs).second}",
            hex,
        )
    }

    suspend fun fetchOpenSkyAirportFlights(
        keys: ApiKeys,
        icao: String,
        fromMs: Long,
        toMs: Long,
        arrivals: Boolean,
    ): List<FlightSearchResult> {
        val code = icao.trim().uppercase()
        if (code.length != 4) return emptyList()
        val (begin, end) = unixRange(fromMs, toMs)
        val kind = if (arrivals) "arrival" else "departure"
        val legs = fetchOpenSkyFlightList(
            keys,
            "https://opensky-network.org/api/flights/$kind?airport=$code&begin=$begin&end=$end",
        )
        return legs.mapNotNull { o ->
            val number = compactCallsign(o.callsign)
            if (number.isBlank()) return@mapNotNull null
            FlightSearchResult(
                flightNumber = number,
                airlineIata = Regex("^([A-Z]{2})\\d").find(number)?.groupValues?.get(1),
                fromIata = o.fromIcao,
                toIata = o.toIcao,
                scheduledDep = o.firstSeen,
                scheduledArr = o.lastSeen,
                status = if (arrivals) FlightStatus.LANDED else FlightStatus.DEPARTED,
                icao24 = o.icao24,
                callsign = o.callsign,
                source = "opensky",
            )
        }
    }

    private fun unixRange(fromMs: Long, toMs: Long): Pair<Long, Long> {
        val begin = (fromMs / 1000L).coerceAtLeast(0L)
        val end = (toMs / 1000L).coerceAtLeast(begin + 60L)
        return begin to end
    }

    private suspend fun fetchOpenSkyFlightList(
        keys: ApiKeys,
        url: String,
        fallbackHex: String? = null,
    ): List<HistoricOpenSkyLeg> {
        val token = openSkyBearer(keys)?.let { sanitizeApiCredential(it) }
        return try {
            val req = Request.Builder().url(url).header("Accept", "application/json").apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        lastOpenSkyError = redactProviderError("${res.code} ${body.take(80)}")
                        return@withContext emptyList()
                    }
                    lastOpenSkyError = null
                    val arr = if (body.trim().startsWith("[")) JSONArray(body) else JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        HistoricOpenSkyLeg(
                            icao24 = o.optString("icao24").ifBlank { fallbackHex }.orEmpty(),
                            callsign = o.optString("callsign").trim().ifBlank { null },
                            fromIcao = o.optString("estDepartureAirport").ifBlank { null },
                            toIcao = o.optString("estArrivalAirport").ifBlank { null },
                            firstSeen = o.optLong("firstSeen").takeIf { it > 0L }?.times(1000L),
                            lastSeen = o.optLong("lastSeen").takeIf { it > 0L }?.times(1000L),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            lastOpenSkyError = redactProviderError(e.message)
            emptyList()
        }
    }

    private fun positionsFromFr24(rows: List<JSONObject>): List<de.rolfwalker.flightbuddy.core.model.LatLon> {
        val pts = mutableListOf<Triple<Long, Double, Double>>()
        fun add(obj: JSONObject?) {
            if (obj == null) return
            val lat = finite(obj.opt("lat"), obj.opt("latitude"))
            val lon = finite(obj.opt("lon"), obj.opt("lng"), obj.opt("longitude"))
            val at = parseTime(
                obj.opt("timestamp") ?: obj.opt("position_time") ?: obj.opt("seen") ?: obj.opt("datetime"),
            )
            if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                pts += Triple(at ?: 0L, lat, lon)
            }
            val nested = obj.optJSONArray("positions") ?: obj.optJSONArray("trail") ?: obj.optJSONArray("track")
            if (nested != null) {
                for (i in 0 until nested.length()) {
                    val child = nested.optJSONObject(i)
                    if (child != null) {
                        add(child)
                        continue
                    }
                    val arr = nested.optJSONArray(i) ?: continue
                    val a0 = arr.optDouble(0, Double.NaN)
                    val a1 = arr.optDouble(1, Double.NaN)
                    val a2 = arr.optDouble(2, Double.NaN)
                    when {
                        a1.isFinite() && a2.isFinite() && a1 in -90.0..90.0 && a2 in -180.0..180.0 ->
                            pts += Triple((a0.takeIf { it.isFinite() && it > 1_000 }?.toLong()?.let { if (it < 1e12) it * 1000 else it } ?: 0L), a1, a2)
                        a0.isFinite() && a1.isFinite() && a0 in -90.0..90.0 && a1 in -180.0..180.0 ->
                            pts += Triple(0L, a0, a1)
                    }
                }
            }
        }
        rows.forEach { add(it) }
        return pts.sortedBy { it.first }
            .map { de.rolfwalker.flightbuddy.core.model.LatLon(it.second, it.third) }
            .distinct()
    }

    suspend fun fetchOpenSkyTrack(keys: ApiKeys, icao24: String): List<de.rolfwalker.flightbuddy.core.model.LatLon> {
        val hex = icao24.lowercase().trim()
        if (hex.isBlank()) return emptyList()
        val token = openSkyBearer(keys)?.let { sanitizeApiCredential(it) }
        val url = "https://opensky-network.org/api/tracks/all?icao24=$hex&time=0"
        return try {
            val req = Request.Builder().url(url).header("Accept", "application/json").apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@withContext emptyList()
                    val path = JSONObject(res.body?.string().orEmpty()).optJSONArray("path") ?: return@withContext emptyList()
                    (0 until path.length()).mapNotNull { i ->
                        val row = path.optJSONArray(i) ?: return@mapNotNull null
                        val lat = row.optDouble(1, Double.NaN)
                        val lon = row.optDouble(2, Double.NaN)
                        if (!lat.isFinite() || !lon.isFinite()) null
                        else de.rolfwalker.flightbuddy.core.model.LatLon(lat, lon)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchAeroObject(keys: ApiKeys, path: String): JSONObject? = withContext(Dispatchers.IO) {
        val clean = keys.sanitized()
        val endpoint = clean.aeroEndpointOrNull() ?: return@withContext null
        val url = try {
            joinAeroUrl(endpoint.aeroBaseUrl, path)
        } catch (_: Exception) {
            return@withContext null
        }
        val req = Request.Builder().url(url).apply {
            aeroHeaders(clean.aeroKey, endpoint).forEach { addHeader(it.key, it.value) }
        }.build()
        try {
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                val remaining = res.header("x-ratelimit-remaining")?.toIntOrNull()
                    ?: res.header("X-RateLimit-Remaining")?.toIntOrNull()
                lastAeroRemaining = remaining
                if (!res.isSuccessful) {
                    lastAeroError = redactProviderError(httpDetail(res.code, body, res.message))
                    log("aerodatabox", path, res.code, false, lastAeroError, remaining)
                    return@withContext null
                }
                lastAeroError = null
                log("aerodatabox", path, res.code, true, null, remaining)
                if (body.isBlank()) return@withContext null
                JSONObject(body)
            }
        } catch (e: Exception) {
            lastAeroError = redactProviderError(e.message)
            log("aerodatabox", path, null, false, lastAeroError, null)
            null
        }
    }

    private suspend fun fr24JsonArray(keys: ApiKeys, path: String): List<JSONObject> {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank()) return emptyList()
        return try {
            val req = Request.Builder()
                .url("https://fr24api.flightradar24.com$path")
                .header("Accept", "application/json")
                .header("Accept-Version", "v1")
                .header("Authorization", "Bearer $token")
                .build()
            withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    lastFr24Remaining = res.header("x-rate-limit-remaining")?.toIntOrNull()
                    if (!res.isSuccessful) {
                        lastFr24Error = redactProviderError("${res.code} ${body.take(120)}")
                        log("fr24", path, res.code, false, lastFr24Error, lastFr24Remaining)
                        return@withContext emptyList()
                    }
                    lastFr24Error = null
                    log("fr24", path, res.code, true, null, lastFr24Remaining)
                    val root = if (body.trim().startsWith("[")) JSONArray(body)
                    else JSONObject(body).optJSONArray("data") ?: JSONArray()
                    (0 until root.length()).map { root.getJSONObject(it) }
                }
            }
        } catch (e: Exception) {
            lastFr24Error = redactProviderError(e.message)
            log("fr24", path, null, false, lastFr24Error, null)
            emptyList()
        }
    }

    private suspend fun fr24JsonObject(keys: ApiKeys, path: String): JSONObject? {
        val rows = fr24JsonArray(keys, path)
        if (rows.isNotEmpty()) return rows.first()
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("https://fr24api.flightradar24.com$path")
                .header("Accept", "application/json")
                .header("Accept-Version", "v1")
                .header("Authorization", "Bearer $token")
                .build()
            withContext(Dispatchers.IO) {
                http.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    lastFr24Remaining = res.header("x-rate-limit-remaining")?.toIntOrNull()
                    if (!res.isSuccessful) {
                        lastFr24Error = redactProviderError("${res.code} ${body.take(160)}")
                        log("fr24", path, res.code, false, lastFr24Error, lastFr24Remaining)
                        return@withContext null
                    }
                    lastFr24Error = null
                    log("fr24", path, res.code, true, null, lastFr24Remaining)
                    if (body.isBlank()) return@withContext null
                    val trimmed = body.trim()
                    if (trimmed.startsWith("{")) JSONObject(trimmed) else null
                }
            }
        } catch (e: Exception) {
            lastFr24Error = redactProviderError(e.message)
            null
        }
    }

    private fun mapFr24Summary(obj: JSONObject): Fr24Summary? {
        return Fr24Summary(
            fr24Id = obj.optString("fr24_id").ifBlank { null },
            flight = obj.optString("flight").ifBlank { null },
            callsign = obj.optString("callsign").ifBlank { null },
            reg = obj.optString("reg").ifBlank { null },
            type = obj.optString("type").ifBlank { null },
            paintedAs = obj.optString("painted_as").ifBlank { null },
            operatingAs = obj.optString("operating_as").ifBlank { null },
            origIata = obj.optString("orig_iata").ifBlank { null },
            destIata = obj.optString("dest_iata").ifBlank { null },
            destIataActual = obj.optString("dest_iata_actual").ifBlank { null },
            takeoffAt = parseTime(obj.opt("datetime_takeoff")),
            landedAt = parseTime(obj.opt("datetime_landed")),
            firstSeen = parseTime(obj.opt("first_seen")),
            lastSeen = parseTime(obj.opt("last_seen")),
            runwayTakeoff = obj.optString("runway_takeoff").ifBlank { null },
            runwayLanded = obj.optString("runway_landed").ifBlank { null },
            flightTimeSec = obj.optInt("flight_time").takeIf { obj.has("flight_time") && it > 0 },
            actualDistanceKm = obj.optDouble("actual_distance", Double.NaN).takeIf { it.isFinite() },
            circleDistanceKm = obj.optDouble("circle_distance", Double.NaN).takeIf { it.isFinite() },
            ended = obj.optBoolean("flight_ended", false),
        )
    }

    private fun encode(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
}
