package de.rolfwalker.flightbuddy.core.domain

/** OpenSky pads callsigns with spaces (`RGA08   `). Keep letters+digits only. */
fun compactCallsign(value: String?): String =
    value?.replace(Regex("[^A-Za-z0-9]"), "")?.uppercase().orEmpty()

/** ICAO/IATA prefix from an OpenSky callsign (`DLH441` → `DLH`). */
fun callsignPrefix(callsign: String?): String? {
    val compact = compactCallsign(callsign)
    if (compact.isEmpty()) return null
    return Regex("^([A-Z]{2,3})").find(compact)?.groupValues?.get(1)
}

fun isRegaPrefix(callsign: String?): Boolean {
    val compact = compactCallsign(callsign)
    return compact == "RGA" || compact.matches(Regex("^RGA\\d+$"))
}

fun displayTrafficCallsign(callsign: String?, icao24: String): String {
    val compact = compactCallsign(callsign)
    if (compact.isNotEmpty()) return displayFlightNumber(compact)
    return icao24.take(6).uppercase()
}

fun resolveTrafficAirline(callsign: String?): Pair<String?, String?> {
    if (isRegaPrefix(callsign)) return null to "REGA"
    val prefix = callsignPrefix(callsign) ?: return null to null
    AirlineCatalog.lookup(prefix)?.let { return it.iata to it.name }
    if (prefix.length == 2) return prefix to null
    return null to null
}

/** `EK086` → `EK86`, `UAE086` → `UAE86`. */
fun stripFlightZeros(code: String): String {
    val m = Regex("^([A-Z]{2,3})0+(\\d+[A-Z]?)$").find(code) ?: return code
    return m.groupValues[1] + m.groupValues[2]
}

fun flightNumberDigits(raw: String?): String? {
    val compact = compactCallsign(raw)
    val digits = Regex("(\\d+[A-Z]?)$").find(compact)?.groupValues?.get(1) ?: return null
    return digits.trimStart('0').ifBlank { "0" }
}

fun flightNumberIata(raw: String?): String? =
    Regex("^([A-Z]{2})\\d").find(compactCallsign(raw))?.groupValues?.get(1)

/** FR24 flight filters: `EK86`, `EK086`, `EK0086`. */
fun paddedIataFlightNumbers(flightNumber: String?): List<String> {
    val compact = compactCallsign(flightNumber)
    if (compact.isEmpty()) return emptyList()
    val out = linkedSetOf(compact, stripFlightZeros(compact))
    val m = Regex("^([A-Z]{2})(\\d+)([A-Z]?)$").find(compact) ?: return out.toList()
    val prefix = m.groupValues[1]
    val digits = m.groupValues[2].trimStart('0').ifBlank { "0" }
    val suffix = m.groupValues[3]
    for (width in 3..4) {
        out += prefix + digits.padStart(width, '0') + suffix
    }
    return out.toList()
}

/**
 * IATA number `EK86` plus ICAO radio callsign `UAE86` — OpenSky/FR24 use the latter.
 */
fun liveCallsignCandidates(
    flightNumber: String?,
    callsign: String?,
    airlineIcao: String? = null,
    airlineIata: String? = null,
): List<String> {
    val out = linkedSetOf<String>()
    fun add(raw: String?) {
        val compact = compactCallsign(raw)
        if (compact.isEmpty()) return
        out += compact
        out += stripFlightZeros(compact)
    }
    add(callsign)
    add(flightNumber)
    val num = flightNumberDigits(flightNumber) ?: flightNumberDigits(callsign)
    val prefixes = linkedSetOf<String>()
    airlineIcao?.trim()?.uppercase()?.takeIf { it.length in 2..3 }?.let { prefixes += it }
    airlineIata?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { iata ->
        prefixes += iata
        AirlineCatalog.lookup(iata)?.icao?.let { prefixes += it }
    }
    flightNumberIata(flightNumber)?.let { iata ->
        prefixes += iata
        AirlineCatalog.lookup(iata)?.icao?.let { prefixes += it }
    }
    if (num != null) {
        for (p in prefixes) add(p + num)
    }
    return out.toList()
}

fun preferredIcaoCallsign(
    flightNumber: String?,
    callsign: String?,
    airlineIcao: String? = null,
    airlineIata: String? = null,
): String? = liveCallsignCandidates(flightNumber, callsign, airlineIcao, airlineIata)
    .firstOrNull { compactCallsign(it).let { c -> callsignPrefix(c)?.length == 3 && flightNumberDigits(c) != null } }

fun callsignMatches(observed: String?, candidates: Collection<String>): Boolean {
    val got = stripFlightZeros(compactCallsign(observed))
    if (got.isEmpty()) return false
    return candidates.any { want ->
        val c = stripFlightZeros(compactCallsign(want))
        c.isNotEmpty() && (got == c || got.startsWith(c) || c.startsWith(got))
    }
}
