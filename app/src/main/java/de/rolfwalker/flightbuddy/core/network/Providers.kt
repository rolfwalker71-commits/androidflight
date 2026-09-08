package de.rolfwalker.flightbuddy.core.network

import de.rolfwalker.flightbuddy.core.data.db.ApiLogDao
import de.rolfwalker.flightbuddy.core.data.db.ApiLogEntity
import de.rolfwalker.flightbuddy.core.data.prefs.ApiKeys
import de.rolfwalker.flightbuddy.core.data.prefs.INVALID_API_CREDENTIAL_CHARS
import de.rolfwalker.flightbuddy.core.data.prefs.isInvalidApiCredentialException
import de.rolfwalker.flightbuddy.core.data.prefs.redactProviderError
import de.rolfwalker.flightbuddy.core.data.prefs.sanitizeApiCredential
import de.rolfwalker.flightbuddy.core.data.prefs.sanitized
import de.rolfwalker.flightbuddy.core.domain.mapProviderStatus
import de.rolfwalker.flightbuddy.core.model.AircraftPhoto
import de.rolfwalker.flightbuddy.core.model.FlightSearchResult
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.SearchReason
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class LiveFix(
    val lat: Double,
    val lon: Double,
    val altitudeFt: Double? = null,
    val velocityKts: Double? = null,
    val heading: Double? = null,
    val onGround: Boolean = false,
    val icao24: String? = null,
    val callsign: String? = null,
    val squawk: String? = null,
    val source: String,
    val observedAt: Long = System.currentTimeMillis(),
)

data class AeroLookup(
    val flights: List<FlightSearchResult>,
    val reason: SearchReason,
    val httpStatus: Int? = null,
)

data class TrafficState(
    val icao24: String,
    val callsign: String?,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Double?,
    val velocityKts: Double?,
    val heading: Double?,
    val onGround: Boolean,
    val squawk: String?,
    val country: String?,
)

class ProviderClients(
    private val logs: ApiLogDao,
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private var openSkyToken: String? = null
    private var openSkyTokenExp = 0L
    private val lastOpenSky = AtomicLong(0)
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
        if (raw == null) return null
        val s = when (raw) {
            is String -> raw
            is JSONObject -> raw.optString("utc").ifBlank { raw.optString("local") }
            else -> raw.toString()
        }.trim()
        if (s.isBlank()) return null
        val spaced = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})(?::(\d{2}))?(Z|[+-]\d{2}:\d{2})$""").find(s)
        val iso = if (spaced != null) {
            "${spaced.groupValues[1]}T${spaced.groupValues[2]}:${spaced.groupValues[3].ifBlank { "00" }}${spaced.groupValues[4]}"
        } else s
        return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(iso).toEpochMilli() }
            .getOrNull()
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

    private fun mapAero(obj: JSONObject): FlightSearchResult {
        val dep = obj.optJSONObject("departure")
        val arr = obj.optJSONObject("arrival")
        val airline = obj.optJSONObject("airline")
        val aircraft = obj.optJSONObject("aircraft")
        val loc = obj.optJSONObject("location") ?: obj.optJSONObject("position")
        val depAirport = dep?.optJSONObject("airport")
        val arrAirport = arr?.optJSONObject("airport")
        return FlightSearchResult(
            flightNumber = obj.optString("number").replace(Regex("\\s+"), ""),
            airlineName = airline?.optString("name")?.ifBlank { null },
            airlineIata = airline?.optString("iata")?.ifBlank { null },
            airlineIcao = airline?.optString("icao")?.ifBlank { null },
            fromIata = depAirport?.optString("iata")?.ifBlank { null },
            toIata = arrAirport?.optString("iata")?.ifBlank { null },
            fromCity = (depAirport?.optString("municipalityName") ?: depAirport?.optString("name"))?.ifBlank { null },
            toCity = (arrAirport?.optString("municipalityName") ?: arrAirport?.optString("name"))?.ifBlank { null },
            scheduledDep = parseTime(dep?.opt("scheduledTimeUtc") ?: dep?.opt("scheduledTime") ?: dep?.opt("scheduledTimeLocal")),
            scheduledArr = parseTime(arr?.opt("scheduledTimeUtc") ?: arr?.opt("scheduledTime") ?: arr?.opt("scheduledTimeLocal")),
            estimatedDep = parseTime(dep?.opt("revisedTimeUtc") ?: dep?.opt("revisedTime") ?: dep?.opt("predictedTime")),
            estimatedArr = parseTime(arr?.opt("revisedTimeUtc") ?: arr?.opt("revisedTime") ?: arr?.opt("predictedTime")),
            actualDep = parseTime(dep?.opt("actualTimeUtc") ?: dep?.opt("actualTime") ?: dep?.opt("runwayTime")),
            actualArr = parseTime(arr?.opt("actualTimeUtc") ?: arr?.opt("actualTime") ?: arr?.opt("runwayTime")),
            status = mapProviderStatus(obj.optString("status").ifBlank { null }),
            gate = dep?.optString("gate")?.ifBlank { null },
            terminal = dep?.optString("terminal")?.ifBlank { null },
            arrivalGate = arr?.optString("gate")?.ifBlank { null },
            arrivalTerminal = arr?.optString("terminal")?.ifBlank { null },
            aircraftType = aircraft?.optString("model")?.ifBlank { null },
            registration = aircraft?.optString("reg")?.ifBlank { null },
            icao24 = (aircraft?.optString("modeS") ?: aircraft?.optString("icao24"))?.ifBlank { null }?.lowercase(),
            callsign = (obj.optString("callSign").ifBlank { obj.optString("callsign") }).ifBlank { null },
            delayMinutes = dep?.optJSONObject("delay")?.optInt("minutes")?.takeIf { it != 0 },
            fromLat = loc?.let { finite(it.opt("lat"), it.opt("latitude")) },
            fromLon = loc?.let { finite(it.opt("lon"), it.opt("longitude")) },
            source = "aerodatabox",
        )
    }

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
        val from = if (user) date.minusDays(1) else date
        val to = if (user) date.plusDays(1) else date
        val rangePath = if (from == to) {
            "/flights/number/${encode(number)}/$from?dateLocalRole=Both"
        } else {
            "/flights/number/${encode(number)}/$from/$to?dateLocalRole=Both"
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
            val dayPath = "/flights/number/${encode(number)}/$date?dateLocalRole=Both"
            lookup = fetchAeroList(keys, dayPath)
        }
        return lookup
    }

    suspend fun searchAeroAirportDepartures(keys: ApiKeys, fromIata: String, date: LocalDate): AeroLookup {
        if (!keys.hasAeroDataBox()) return AeroLookup(emptyList(), SearchReason.UNCONFIGURED)
        val path = "/flights/airports/iata/${encode(fromIata)}/$date/$date?direction=Departure&withCancelled=true"
        return fetchAeroList(keys, path)
    }

    suspend fun lookupAeroLive(keys: ApiKeys, flightNumber: String, date: LocalDate): FlightSearchResult? {
        if (!keys.hasAeroDataBox()) return null
        val path = "/flights/number/${encode(normalizeFlightNumber(flightNumber))}/${date}?dateLocalRole=Both&withLocation=true"
        return fetchAeroList(keys, path).flights.firstOrNull()
    }

    private suspend fun fetchAeroList(keys: ApiKeys, path: String): AeroLookup {
        val clean = keys.sanitized()
        val endpoint = clean.aeroEndpointOrNull() ?: return AeroLookup(emptyList(), SearchReason.UNCONFIGURED)
        val url = joinAeroUrl(endpoint.aeroBaseUrl, path)
        return try {
            val req = Request.Builder().url(url).apply {
                aeroHeaders(clean.aeroKey, endpoint).forEach { addHeader(it.key, it.value) }
            }.build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                val remaining = res.header("x-ratelimit-remaining")?.toIntOrNull()
                    ?: res.header("X-RateLimit-Remaining")?.toIntOrNull()
                lastAeroRemaining = remaining
                if (res.code == 204) {
                    lastAeroError = null
                    log("aerodatabox", path, res.code, true, null, remaining)
                    return AeroLookup(emptyList(), SearchReason.EMPTY, res.code)
                }
                if (!res.isSuccessful) {
                    lastAeroError = redactProviderError("${res.code} ${body.take(180)}")
                    log("aerodatabox", path, res.code, false, lastAeroError, remaining)
                    return AeroLookup(emptyList(), classifyAero(res.code, body), res.code)
                }
                lastAeroError = null
                log("aerodatabox", path, res.code, true, null, remaining)
                val rows = rowsFrom(body).map { mapAero(it) }.filter { it.flightNumber.isNotBlank() || it.fromIata != null }
                AeroLookup(rows, if (rows.isEmpty()) SearchReason.EMPTY else SearchReason.OK, res.code)
            }
        } catch (e: Exception) {
            if (isInvalidApiCredentialException(e)) {
                lastAeroError = INVALID_API_CREDENTIAL_CHARS
                log("aerodatabox", path, null, false, lastAeroError, null)
                return AeroLookup(emptyList(), SearchReason.INVALID_API_KEY)
            }
            lastAeroError = redactProviderError(e.message)
            log("aerodatabox", path, null, false, lastAeroError, null)
            AeroLookup(emptyList(), SearchReason.NETWORK_ERROR)
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
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    lastOpenSkyError = "token ${res.code}"
                    return null
                }
                val obj = JSONObject(text)
                openSkyToken = obj.optString("access_token").ifBlank { null }
                val ttl = (obj.optInt("expires_in", 1800) - 60).coerceAtLeast(60)
                openSkyTokenExp = System.currentTimeMillis() + ttl * 1000L
                openSkyToken
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
    ): List<TrafficState> {
        val min = keys.openSkyMinIntervalMs.toLong().coerceAtLeast(90_000)
        val since = System.currentTimeMillis() - lastOpenSky.get()
        if (lastOpenSky.get() != 0L && since < min) return emptyList()
        lastOpenSky.set(System.currentTimeMillis())
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
            http.newCall(req).execute().use { res ->
                lastOpenSkyRemaining = res.header("X-Rate-Limit-Remaining")?.toIntOrNull()
                    ?: res.header("x-rate-limit-remaining")?.toIntOrNull()
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    lastOpenSkyError = redactProviderError("${res.code} ${body.take(120)}")
                    log("opensky", qs, res.code, false, lastOpenSkyError, lastOpenSkyRemaining)
                    return emptyList()
                }
                lastOpenSkyError = null
                log("opensky", qs, res.code, true, null, lastOpenSkyRemaining)
                val states = JSONObject(body).optJSONArray("states") ?: return emptyList()
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
                        altitudeFt = row.optDouble(7, Double.NaN).takeIf { it.isFinite() }?.times(3.28084),
                        velocityKts = row.optDouble(9, Double.NaN).takeIf { it.isFinite() }?.div(0.514444),
                        heading = row.optDouble(10, Double.NaN).takeIf { it.isFinite() },
                        onGround = row.optBoolean(8, false),
                        squawk = row.optString(14).ifBlank { null },
                        country = row.optString(2).ifBlank { null },
                    )
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

    suspend fun fetchFr24(keys: ApiKeys, flightNumber: String?, callsign: String?, icao24: String?, lat: Double?, lon: Double?): LiveFix? {
        val token = sanitizeApiCredential(keys.fr24Token)
        if (token.isBlank() || !keys.fr24Enabled) return null
        val min = keys.fr24MinIntervalMs.toLong().coerceAtLeast(180_000)
        val since = System.currentTimeMillis() - lastFr24.get()
        if (lastFr24.get() != 0L && since < min) return null
        lastFr24.set(System.currentTimeMillis())
        val params = mutableListOf("limit=1", "data_sources=ADSB,MLAT,UAT")
        val path = when {
            !callsign.isNullOrBlank() -> {
                params += "callsigns=${callsign.replace(" ", "")}"
                "/api/live/flight-positions/light?${params.joinToString("&")}"
            }
            !flightNumber.isNullOrBlank() -> {
                params += "flights=${flightNumber.uppercase().replace(Regex("[\\s-]+"), "")}"
                "/api/live/flight-positions/light?${params.joinToString("&")}"
            }
            icao24 != null && lat != null && lon != null -> {
                val n = (lat + 1.5).coerceAtMost(90.0)
                val s = (lat - 1.5).coerceAtLeast(-90.0)
                params += "bounds=$n,$s,${lon - 1.5},${lon + 1.5}"
                params[0] = "limit=5"
                "/api/live/flight-positions/light?${params.joinToString("&")}"
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
            http.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                lastFr24Remaining = res.header("x-rate-limit-remaining")?.toIntOrNull()
                if (!res.isSuccessful) {
                    lastFr24Error = redactProviderError("${res.code} ${body.take(120)}")
                    log("fr24", path, res.code, false, lastFr24Error, lastFr24Remaining)
                    return null
                }
                lastFr24Error = null
                log("fr24", path, res.code, true, null, lastFr24Remaining)
                val root = if (body.trim().startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("data") ?: JSONArray()
                if (root.length() == 0) return null
                val row = (0 until root.length()).map { root.getJSONObject(it) }.firstOrNull { obj ->
                    icao24 == null || obj.optString("hex").equals(icao24, true)
                } ?: root.getJSONObject(0)
                val rlat = row.optDouble("lat", Double.NaN)
                val rlon = row.optDouble("lon", Double.NaN)
                if (!rlat.isFinite() || !rlon.isFinite()) return null
                val alt = row.optDouble("alt", Double.NaN).takeIf { it.isFinite() }
                LiveFix(
                    lat = rlat,
                    lon = rlon,
                    altitudeFt = alt,
                    velocityKts = row.optDouble("gspeed", Double.NaN).takeIf { it.isFinite() },
                    heading = row.optDouble("track", Double.NaN).takeIf { it.isFinite() },
                    onGround = alt != null && alt <= 0,
                    icao24 = row.optString("hex").ifBlank { null }?.lowercase(),
                    callsign = row.optString("callsign").ifBlank { null },
                    source = "fr24",
                )
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
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val photos = JSONObject(res.body?.string().orEmpty()).optJSONArray("photos") ?: return null
                if (photos.length() == 0) return null
                val p = photos.getJSONObject(0)
                val thumb = p.optJSONObject("thumbnail_large") ?: p.optJSONObject("thumbnail")
                val src = thumb?.optString("src")?.ifBlank { null } ?: return null
                AircraftPhoto(
                    url = src,
                    webUrl = p.optString("link").ifBlank { null },
                    photographer = p.optString("photographer").ifBlank { null },
                    source = "Planespotters.net",
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
}
