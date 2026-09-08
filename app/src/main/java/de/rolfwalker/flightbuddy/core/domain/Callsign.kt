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
