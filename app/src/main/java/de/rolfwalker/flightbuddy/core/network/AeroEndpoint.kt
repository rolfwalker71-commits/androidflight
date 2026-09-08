package de.rolfwalker.flightbuddy.core.network

import de.rolfwalker.flightbuddy.core.data.prefs.ApiKeys
import de.rolfwalker.flightbuddy.core.data.prefs.sanitizeApiCredential
import java.net.URI

/** Official AeroDataBox listing on API.Market (not RapidAPI). Same default as FlightBuddy PWA. */
const val AERO_API_MARKET_HOST = "prod.api.market/api/v1/aedbx/aerodatabox"
const val AERO_API_MARKET_BASE_URL = "https://prod.api.market/api/v1/aedbx/aerodatabox"

data class AeroEndpoint(
    val aeroBaseUrl: String,
    val aeroHost: String,
    val marketplace: String,
)

private fun ensureHttps(raw: String): String {
    val trimmed = raw.trim().trimEnd('/').trimStart('/')
    if (trimmed.isBlank()) return AERO_API_MARKET_BASE_URL
    return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed.trimEnd('/')
    } else {
        "https://$trimmed"
    }
}

/**
 * Accepts a full BASE_URL and/or a HOST (hostname, host+path, or URL without scheme).
 * Mirrors FlightBuddy PWA `resolveAeroEndpoint` in lib/server-env.ts, but also
 * adds https when a BASE_URL was stored as a host path (no scheme).
 * Blank inputs default to the official API.Market listing — same as the PWA.
 */
fun resolveAeroEndpoint(baseUrl: String?, host: String?): AeroEndpoint {
    val fromBase = sanitizeApiCredential(baseUrl.orEmpty())
    val fromHost = sanitizeApiCredential(host.orEmpty())
    var raw = fromBase
    if (raw.isBlank() && fromHost.isNotBlank()) {
        raw = fromHost
    }
    if (raw.isBlank()) raw = AERO_API_MARKET_BASE_URL
    var aeroBaseUrl = ensureHttps(raw)
    if (aeroBaseUrl.contains("api.market", ignoreCase = true) &&
        !aeroBaseUrl.contains("/api/v1/")
    ) {
        aeroBaseUrl = AERO_API_MARKET_BASE_URL
    }
    val marketplace = if (aeroBaseUrl.contains("rapidapi.com", ignoreCase = true)) "rapidapi" else "apimarket"
    val aeroHost = runCatching { URI(aeroBaseUrl).host }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: if (marketplace == "apimarket") "prod.api.market" else fromHost.substringBefore("/")
    return AeroEndpoint(aeroBaseUrl, aeroHost, marketplace)
}

fun joinAeroUrl(baseUrl: String, path: String): String {
    val base = resolveAeroEndpoint(baseUrl, null).aeroBaseUrl.trimEnd('/')
    val suffix = if (path.startsWith("/")) path else "/$path"
    return base + suffix
}

fun ApiKeys.aeroEndpointOrNull(): AeroEndpoint? {
    val key = sanitizeApiCredential(aeroKey)
    return if (key.isBlank()) null else resolveAeroEndpoint(aeroBaseUrl, aeroHost)
}

fun ApiKeys.hasAeroDataBox(): Boolean = sanitizeApiCredential(aeroKey).isNotBlank()

/** Value shown and edited in Settings — prefer the full https base URL. */
fun ApiKeys.aeroEndpointInput(): String =
    aeroBaseUrl.ifBlank { aeroHost }.ifBlank { AERO_API_MARKET_BASE_URL }

fun ApiKeys.withAeroEndpointInput(raw: String): ApiKeys {
    val trimmed = sanitizeApiCredential(raw)
    if (trimmed.isBlank()) {
        return copy(aeroHost = AERO_API_MARKET_HOST, aeroBaseUrl = AERO_API_MARKET_BASE_URL)
    }
    return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        val resolved = resolveAeroEndpoint(trimmed, "")
        copy(
            aeroBaseUrl = resolved.aeroBaseUrl,
            aeroHost = if (resolved.marketplace == "apimarket") AERO_API_MARKET_HOST else resolved.aeroHost,
        )
    } else {
        val resolved = resolveAeroEndpoint("", trimmed)
        copy(
            aeroHost = trimmed,
            aeroBaseUrl = resolved.aeroBaseUrl,
        )
    }
}

fun ApiKeys.withResolvedAeroDefaults(): ApiKeys {
    val host = sanitizeApiCredential(aeroHost)
    val resolved = resolveAeroEndpoint(aeroBaseUrl, host)
    return copy(
        aeroBaseUrl = resolved.aeroBaseUrl,
        aeroHost = host.ifBlank { AERO_API_MARKET_HOST },
    )
}

fun normalizeFlightNumber(flightNumber: String): String =
    flightNumber.uppercase().replace(Regex("[\\s-]+"), "")
