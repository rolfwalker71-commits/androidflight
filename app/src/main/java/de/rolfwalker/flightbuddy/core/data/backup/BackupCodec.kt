package de.rolfwalker.flightbuddy.core.data.backup

import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.prefs.ApiKeys
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.MapStyleId
import de.rolfwalker.flightbuddy.core.model.PollPhase
import de.rolfwalker.flightbuddy.core.model.ThemeMode
import de.rolfwalker.flightbuddy.core.model.Units
import de.rolfwalker.flightbuddy.core.network.aeroEndpointInput
import de.rolfwalker.flightbuddy.core.network.withAeroEndpointInput
import de.rolfwalker.flightbuddy.core.normalizeAppLanguage
import org.json.JSONArray
import org.json.JSONObject

const val BACKUP_FORMAT = "flightbuddy-backup"
const val BACKUP_VERSION = 2

class BackupFormatException(message: String) : IllegalArgumentException(message)

data class DeviceBackup(
    val keys: ApiKeys,
    val settings: UserPrefs = UserPrefs(),
    val flights: List<FlightEntity>,
    val exportedAt: Long = System.currentTimeMillis(),
)

object BackupCodec {
    fun encode(backup: DeviceBackup): String {
        val root = JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("version", BACKUP_VERSION)
            .put("exportedAt", backup.exportedAt)
            .put("settings", encodeSettings(backup.settings, backup.keys))
            .put("keys", encodeKeys(backup.keys))
        val flights = JSONArray()
        backup.flights.forEach { flights.put(encodeFlight(it)) }
        root.put("flights", flights)
        return root.toString(2)
    }

    fun decode(json: String): DeviceBackup {
        return runCatching {
            val root = JSONObject(json)
            if (root.optString("format") != BACKUP_FORMAT) {
                throw BackupFormatException("unknown format")
            }
            val settingsObj = root.optJSONObject("settings")
            val nestedKeys = settingsObj?.optJSONObject("keys")
            val topKeys = root.optJSONObject("keys")
            val keys = when {
                nestedKeys != null -> decodeKeys(nestedKeys)
                topKeys != null -> decodeKeys(topKeys)
                else -> ApiKeys()
            }
            val settings = if (settingsObj != null) decodeSettings(settingsObj) else UserPrefs()
            val flights = mutableListOf<FlightEntity>()
            val array = root.optJSONArray("flights") ?: JSONArray()
            for (i in 0 until array.length()) {
                decodeFlight(array.getJSONObject(i))?.let { flights += it }
            }
            DeviceBackup(
                keys = keys,
                settings = settings,
                flights = flights,
                exportedAt = root.optLong("exportedAt", 0L),
            )
        }.getOrElse { e ->
            if (e is BackupFormatException) throw e
            throw BackupFormatException(e.message ?: "not json")
        }
    }

    private fun encodeSettings(prefs: UserPrefs, keys: ApiKeys) = JSONObject()
        .put("theme", prefs.theme.name)
        .put("language", prefs.language)
        .put("units", prefs.units.name)
        .put("mapStyle", prefs.mapStyle.name)
        .put("gateChanges", prefs.gateChanges)
        .put("delaysStatus", prefs.delaysStatus)
        .put("preflight2h", prefs.preflight2h)
        .put("gateClose", prefs.gateClose)
        .put("arrivalSoon", prefs.arrivalSoon)
        .put("objectAlerts", prefs.objectAlerts)
        .put("squawkAlerts", prefs.squawkAlerts)
        .put("liveNotification", prefs.liveNotification)
        .put("batteryPromptShown", prefs.batteryPromptShown)
        .put("keys", encodeKeys(keys))

    private fun decodeSettings(obj: JSONObject) = UserPrefs(
        theme = enumOr(obj.optString("theme"), ThemeMode.DARK),
        language = normalizeAppLanguage(obj.optNullableString("language")),
        units = enumOr(obj.optString("units"), Units.METRIC),
        mapStyle = enumOr(obj.optString("mapStyle"), MapStyleId.DARK),
        gateChanges = obj.optBoolean("gateChanges", true),
        delaysStatus = obj.optBoolean("delaysStatus", true),
        preflight2h = obj.optBoolean("preflight2h", true),
        gateClose = obj.optBoolean("gateClose", true),
        arrivalSoon = obj.optBoolean("arrivalSoon", true),
        objectAlerts = obj.optBoolean("objectAlerts", true),
        squawkAlerts = obj.optBoolean("squawkAlerts", true),
        liveNotification = obj.optBoolean("liveNotification", true),
        batteryPromptShown = obj.optBoolean("batteryPromptShown", false),
    )

    private fun encodeKeys(keys: ApiKeys) = JSONObject()
        .put("openSkyUsername", keys.openSkyUsername)
        .put("openSkyPassword", keys.openSkyPassword)
        .put("aeroKey", keys.aeroKey)
        .put("aeroEndpoint", keys.aeroEndpointInput())
        .put("aeroBaseUrl", keys.aeroBaseUrl)
        .put("aeroHost", keys.aeroHost)
        .put("fr24Token", keys.fr24Token)
        .put("fr24Enabled", keys.fr24Enabled)
        .put("fr24MinIntervalMs", keys.fr24MinIntervalMs)
        .put("openSkyMinIntervalMs", keys.openSkyMinIntervalMs)

    private fun decodeKeys(obj: JSONObject): ApiKeys {
        val parsed = ApiKeys(
            openSkyUsername = obj.optString("openSkyUsername"),
            openSkyPassword = obj.optString("openSkyPassword"),
            aeroKey = obj.optString("aeroKey"),
            aeroBaseUrl = obj.optString("aeroBaseUrl"),
            aeroHost = obj.optString("aeroHost"),
            fr24Token = obj.optString("fr24Token"),
            fr24Enabled = obj.optBoolean("fr24Enabled", true),
            fr24MinIntervalMs = obj.optInt("fr24MinIntervalMs", 180_000),
            openSkyMinIntervalMs = obj.optInt("openSkyMinIntervalMs", 90_000),
        )
        val endpoint = obj.optString("aeroEndpoint").trim()
        if (parsed.aeroBaseUrl.isBlank() && parsed.aeroHost.isBlank() && endpoint.isNotBlank()) {
            return parsed.withAeroEndpointInput(endpoint)
        }
        return parsed
    }

    private fun encodeFlight(f: FlightEntity): JSONObject {
        val obj = JSONObject()
            .put("id", f.id)
            .put("flightNumber", f.flightNumber)
            .put("scheduledDep", f.scheduledDep)
            .put("status", f.status.name)
            .put("pollPhase", f.pollPhase.name)
            .put("pushAlerts", f.pushAlerts)
            .put("trackDaily", f.trackDaily)
            .put("inLogbook", f.inLogbook)
            .put("reminderPreflightSent", f.reminderPreflightSent)
            .put("reminderGateCloseSent", f.reminderGateCloseSent)
            .put("reminderArrivalSent", f.reminderArrivalSent)
            .put("createdAt", f.createdAt)
            .put("updatedAt", f.updatedAt)
        putIfPresent(obj, "airlineName", f.airlineName)
        putIfPresent(obj, "airlineIata", f.airlineIata)
        putIfPresent(obj, "airlineIcao", f.airlineIcao)
        putIfPresent(obj, "fromIata", f.fromIata)
        putIfPresent(obj, "toIata", f.toIata)
        putIfPresent(obj, "fromCity", f.fromCity)
        putIfPresent(obj, "toCity", f.toCity)
        putIfPresent(obj, "fromCountry", f.fromCountry)
        putIfPresent(obj, "toCountry", f.toCountry)
        putIfPresent(obj, "fromTimezone", f.fromTimezone)
        putIfPresent(obj, "toTimezone", f.toTimezone)
        putIfPresent(obj, "fromLat", f.fromLat)
        putIfPresent(obj, "fromLon", f.fromLon)
        putIfPresent(obj, "toLat", f.toLat)
        putIfPresent(obj, "toLon", f.toLon)
        putIfPresent(obj, "scheduledArr", f.scheduledArr)
        putIfPresent(obj, "estimatedDep", f.estimatedDep)
        putIfPresent(obj, "estimatedArr", f.estimatedArr)
        putIfPresent(obj, "actualDep", f.actualDep)
        putIfPresent(obj, "actualArr", f.actualArr)
        putIfPresent(obj, "gate", f.gate)
        putIfPresent(obj, "terminal", f.terminal)
        putIfPresent(obj, "arrivalGate", f.arrivalGate)
        putIfPresent(obj, "arrivalTerminal", f.arrivalTerminal)
        putIfPresent(obj, "baggageBelt", f.baggageBelt)
        putIfPresent(obj, "checkInDesk", f.checkInDesk)
        putIfPresent(obj, "delayMinutes", f.delayMinutes)
        putIfPresent(obj, "arrivalDelayMinutes", f.arrivalDelayMinutes)
        putIfPresent(obj, "codeshares", f.codeshares)
        putIfPresent(obj, "isCargo", f.isCargo)
        putIfPresent(obj, "paintedAs", f.paintedAs)
        putIfPresent(obj, "operatingAs", f.operatingAs)
        putIfPresent(obj, "fr24Id", f.fr24Id)
        putIfPresent(obj, "runwayDep", f.runwayDep)
        putIfPresent(obj, "runwayArr", f.runwayArr)
        putIfPresent(obj, "runwayDepAt", f.runwayDepAt)
        putIfPresent(obj, "runwayArrAt", f.runwayArrAt)
        putIfPresent(obj, "firstSeenAt", f.firstSeenAt)
        putIfPresent(obj, "lastSeenAtFr24", f.lastSeenAtFr24)
        putIfPresent(obj, "taxiOutMin", f.taxiOutMin)
        putIfPresent(obj, "taxiInMin", f.taxiInMin)
        putIfPresent(obj, "actualDistanceKm", f.actualDistanceKm)
        putIfPresent(obj, "circleDistanceKm", f.circleDistanceKm)
        putIfPresent(obj, "flightTimeSec", f.flightTimeSec)
        putIfPresent(obj, "depMetar", f.depMetar)
        putIfPresent(obj, "arrMetar", f.arrMetar)
        putIfPresent(obj, "depAirportDelayMin", f.depAirportDelayMin)
        putIfPresent(obj, "arrAirportDelayMin", f.arrAirportDelayMin)
        putIfPresent(obj, "inboundFlight", f.inboundFlight)
        putIfPresent(obj, "inboundDelayMin", f.inboundDelayMin)
        putIfPresent(obj, "punctualityMedianMin", f.punctualityMedianMin)
        putIfPresent(obj, "punctualitySample", f.punctualitySample)
        putIfPresent(obj, "timelineJson", f.timelineJson)
        putIfPresent(obj, "aircraftType", f.aircraftType)
        putIfPresent(obj, "registration", f.registration)
        putIfPresent(obj, "icao24", f.icao24)
        putIfPresent(obj, "callsign", f.callsign)
        putIfPresent(obj, "lastLat", f.lastLat)
        putIfPresent(obj, "lastLon", f.lastLon)
        putIfPresent(obj, "lastAltitudeFt", f.lastAltitudeFt)
        putIfPresent(obj, "lastVelocityKts", f.lastVelocityKts)
        putIfPresent(obj, "lastHeading", f.lastHeading)
        putIfPresent(obj, "lastOnGround", f.lastOnGround)
        putIfPresent(obj, "lastPositionAt", f.lastPositionAt)
        putIfPresent(obj, "lastSquawk", f.lastSquawk)
        putIfPresent(obj, "lastStatusSource", f.lastStatusSource)
        putIfPresent(obj, "nextPollAt", f.nextPollAt)
        putIfPresent(obj, "seat", f.seat)
        putIfPresent(obj, "notes", f.notes)
        return obj
    }

    private fun decodeFlight(obj: JSONObject): FlightEntity? {
        val id = obj.optString("id").trim()
        val number = obj.optString("flightNumber").trim()
        if (id.isEmpty() || number.isEmpty() || !obj.has("scheduledDep")) return null
        val scheduledDep = obj.optLong("scheduledDep")
        if (scheduledDep == 0L && obj.opt("scheduledDep") !is Number) return null
        val now = System.currentTimeMillis()
        return FlightEntity(
            id = id,
            flightNumber = number,
            airlineName = obj.optNullableString("airlineName"),
            airlineIata = obj.optNullableString("airlineIata"),
            airlineIcao = obj.optNullableString("airlineIcao"),
            fromIata = obj.optNullableString("fromIata"),
            toIata = obj.optNullableString("toIata"),
            fromCity = obj.optNullableString("fromCity"),
            toCity = obj.optNullableString("toCity"),
            fromCountry = obj.optNullableString("fromCountry"),
            toCountry = obj.optNullableString("toCountry"),
            fromTimezone = obj.optNullableString("fromTimezone"),
            toTimezone = obj.optNullableString("toTimezone"),
            fromLat = obj.optNullableDouble("fromLat"),
            fromLon = obj.optNullableDouble("fromLon"),
            toLat = obj.optNullableDouble("toLat"),
            toLon = obj.optNullableDouble("toLon"),
            scheduledDep = scheduledDep,
            scheduledArr = obj.optNullableLong("scheduledArr"),
            estimatedDep = obj.optNullableLong("estimatedDep"),
            estimatedArr = obj.optNullableLong("estimatedArr"),
            actualDep = obj.optNullableLong("actualDep"),
            actualArr = obj.optNullableLong("actualArr"),
            status = runCatching { FlightStatus.valueOf(obj.optString("status", "UNKNOWN")) }
                .getOrDefault(FlightStatus.UNKNOWN),
            gate = obj.optNullableString("gate"),
            terminal = obj.optNullableString("terminal"),
            arrivalGate = obj.optNullableString("arrivalGate"),
            arrivalTerminal = obj.optNullableString("arrivalTerminal"),
            baggageBelt = obj.optNullableString("baggageBelt"),
            checkInDesk = obj.optNullableString("checkInDesk"),
            delayMinutes = obj.optNullableInt("delayMinutes"),
            arrivalDelayMinutes = obj.optNullableInt("arrivalDelayMinutes"),
            codeshares = obj.optNullableString("codeshares"),
            isCargo = obj.optNullableBoolean("isCargo"),
            paintedAs = obj.optNullableString("paintedAs"),
            operatingAs = obj.optNullableString("operatingAs"),
            fr24Id = obj.optNullableString("fr24Id"),
            runwayDep = obj.optNullableString("runwayDep"),
            runwayArr = obj.optNullableString("runwayArr"),
            runwayDepAt = obj.optNullableLong("runwayDepAt"),
            runwayArrAt = obj.optNullableLong("runwayArrAt"),
            firstSeenAt = obj.optNullableLong("firstSeenAt"),
            lastSeenAtFr24 = obj.optNullableLong("lastSeenAtFr24"),
            taxiOutMin = obj.optNullableInt("taxiOutMin"),
            taxiInMin = obj.optNullableInt("taxiInMin"),
            actualDistanceKm = obj.optNullableDouble("actualDistanceKm"),
            circleDistanceKm = obj.optNullableDouble("circleDistanceKm"),
            flightTimeSec = obj.optNullableInt("flightTimeSec"),
            depMetar = obj.optNullableString("depMetar"),
            arrMetar = obj.optNullableString("arrMetar"),
            depAirportDelayMin = obj.optNullableInt("depAirportDelayMin"),
            arrAirportDelayMin = obj.optNullableInt("arrAirportDelayMin"),
            inboundFlight = obj.optNullableString("inboundFlight"),
            inboundDelayMin = obj.optNullableInt("inboundDelayMin"),
            punctualityMedianMin = obj.optNullableInt("punctualityMedianMin"),
            punctualitySample = obj.optNullableInt("punctualitySample"),
            timelineJson = obj.optNullableString("timelineJson"),
            aircraftType = obj.optNullableString("aircraftType"),
            registration = obj.optNullableString("registration"),
            icao24 = obj.optNullableString("icao24"),
            callsign = obj.optNullableString("callsign"),
            lastLat = obj.optNullableDouble("lastLat"),
            lastLon = obj.optNullableDouble("lastLon"),
            lastAltitudeFt = obj.optNullableDouble("lastAltitudeFt"),
            lastVelocityKts = obj.optNullableDouble("lastVelocityKts"),
            lastHeading = obj.optNullableDouble("lastHeading"),
            lastOnGround = obj.optNullableBoolean("lastOnGround"),
            lastPositionAt = obj.optNullableLong("lastPositionAt"),
            lastSquawk = obj.optNullableString("lastSquawk"),
            lastStatusSource = obj.optNullableString("lastStatusSource"),
            pollPhase = runCatching { PollPhase.valueOf(obj.optString("pollPhase", "INACTIVE")) }
                .getOrDefault(PollPhase.INACTIVE),
            nextPollAt = obj.optNullableLong("nextPollAt"),
            seat = obj.optNullableString("seat"),
            notes = obj.optNullableString("notes"),
            pushAlerts = obj.optBoolean("pushAlerts", true),
            trackDaily = obj.optBoolean("trackDaily", false),
            inLogbook = obj.optBoolean("inLogbook", false),
            reminderPreflightSent = obj.optBoolean("reminderPreflightSent", false),
            reminderGateCloseSent = obj.optBoolean("reminderGateCloseSent", false),
            reminderArrivalSent = obj.optBoolean("reminderArrivalSent", false),
            createdAt = if (obj.has("createdAt")) obj.optLong("createdAt") else now,
            updatedAt = if (obj.has("updatedAt")) obj.optLong("updatedAt") else now,
        )
    }
}

private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

private fun putIfPresent(obj: JSONObject, key: String, value: Any?) {
    if (value != null) obj.put(key, value)
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
}

private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

private fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = optDouble(key)
    return if (v.isNaN()) null else v
}

private fun JSONObject.optNullableBoolean(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return optBoolean(key)
}
