package de.rolfwalker.flightbuddy.core.model

enum class FlightStatus {
    SCHEDULED, DELAYED, BOARDING, GATE_CLOSED, DEPARTED, EN_ROUTE, LANDED, CANCELLED, DIVERTED, UNKNOWN
}

enum class PollPhase { INACTIVE, PREFLIGHT, AIRBORNE, COMPLETE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Units { METRIC, IMPERIAL }

enum class MapStyleId { DARK, VOYAGER, POSITRON, OSM, SATELLITE, TOPO }

enum class ConnectionLevel { MISSED, TIGHT, OK, COMFORTABLE }

enum class SearchReason {
    OK, UNKNOWN_QUERY, UNCONFIGURED, RATE_LIMITED, MONTHLY_QUOTA, NOT_SUBSCRIBED,
    INVALID_API_KEY, HTTP_ERROR, NETWORK_ERROR, EMPTY,
    TIMEOUT, UNKNOWN_HOST, SSL_ERROR, BAD_URL
}

data class LatLon(val lat: Double, val lon: Double)

data class FlightSearchResult(
    val flightNumber: String,
    val airlineName: String? = null,
    val airlineIata: String? = null,
    val airlineIcao: String? = null,
    val fromIata: String? = null,
    val toIata: String? = null,
    val fromCity: String? = null,
    val toCity: String? = null,
    val fromTimezone: String? = null,
    val toTimezone: String? = null,
    val fromLat: Double? = null,
    val fromLon: Double? = null,
    val toLat: Double? = null,
    val toLon: Double? = null,
    val scheduledDep: Long? = null,
    val scheduledArr: Long? = null,
    val estimatedDep: Long? = null,
    val estimatedArr: Long? = null,
    val actualDep: Long? = null,
    val actualArr: Long? = null,
    val status: FlightStatus = FlightStatus.SCHEDULED,
    val gate: String? = null,
    val terminal: String? = null,
    val arrivalGate: String? = null,
    val arrivalTerminal: String? = null,
    val baggageBelt: String? = null,
    val aircraftType: String? = null,
    val registration: String? = null,
    val icao24: String? = null,
    val callsign: String? = null,
    val delayMinutes: Int? = null,
    val arrivalDelayMinutes: Int? = null,
    val checkInDesk: String? = null,
    val codeshares: String? = null,
    val isCargo: Boolean? = null,
    val runwayDepAt: Long? = null,
    val runwayArrAt: Long? = null,
    val fromCountry: String? = null,
    val toCountry: String? = null,
    val liveLat: Double? = null,
    val liveLon: Double? = null,
    val liveAltitudeFt: Double? = null,
    val liveVelocityKts: Double? = null,
    val liveHeading: Double? = null,
    val liveVerticalRateFpm: Double? = null,
    val source: String = "aerodatabox",
    val timesEstimated: Boolean = false,
)

data class ConnectionInfo(
    val layoverMin: Int,
    val level: ConnectionLevel,
    val fromIata: String?,
    val toIata: String?,
)

data class AircraftPhoto(
    val url: String,
    val webUrl: String? = null,
    val photographer: String? = null,
    val source: String,
)

data class ProviderStatus(
    val configured: Boolean,
    val enabled: Boolean = true,
    val lastError: String? = null,
    val lastCallAt: Long? = null,
    val lastStatusCode: Int? = null,
    val remaining: Int? = null,
    val limit: Int? = null,
)
