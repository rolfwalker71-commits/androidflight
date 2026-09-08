package de.rolfwalker.flightbuddy.core.data

import de.rolfwalker.flightbuddy.core.data.db.AirportDao
import de.rolfwalker.flightbuddy.core.data.db.AirportEntity
import de.rolfwalker.flightbuddy.core.data.db.AlertDao
import de.rolfwalker.flightbuddy.core.data.db.AlertEntity
import de.rolfwalker.flightbuddy.core.data.db.FlightDao
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.db.ObjectEventEntity
import de.rolfwalker.flightbuddy.core.data.db.PositionDao
import de.rolfwalker.flightbuddy.core.data.db.PositionEntity
import de.rolfwalker.flightbuddy.core.data.db.TrackedObjectDao
import de.rolfwalker.flightbuddy.core.data.db.TrackedObjectEntity
import de.rolfwalker.flightbuddy.core.domain.FlightQuery
import de.rolfwalker.flightbuddy.core.domain.parseFlightQuery
import de.rolfwalker.flightbuddy.core.domain.resolvePollPhase
import de.rolfwalker.flightbuddy.core.model.FlightSearchResult
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.PollPhase
import de.rolfwalker.flightbuddy.core.model.SearchReason
import de.rolfwalker.flightbuddy.core.network.AeroLookup
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class FlightRepository(
    private val flights: FlightDao,
    private val positions: PositionDao,
    private val alerts: AlertDao,
    private val objects: TrackedObjectDao,
    private val airports: AirportDao,
    private val providers: ProviderClients,
    private val keys: KeysStore,
) {
    fun observeFlights(): Flow<List<FlightEntity>> = flights.observeAll()
    fun observeFlight(id: String): Flow<FlightEntity?> = flights.observeById(id)
    fun observeAlerts(): Flow<List<AlertEntity>> = alerts.observeRecent(50)
    fun observeUnread(): Flow<Int> = alerts.observeUnread()
    fun observeObjects(): Flow<List<TrackedObjectEntity>> = objects.observeAll()
    fun observePositions(flightId: String) = positions.observeForFlight(flightId)
    fun observeAllPositions() = positions.observeAll()

    suspend fun getAirport(iata: String) = airports.get(iata)
    suspend fun getFlight(id: String) = flights.get(id)
    suspend fun listFlights() = flights.listAll()
    suspend fun listActive() = flights.listActive()
    suspend fun listObjects() = objects.listAll()
    suspend fun objectEvents(id: String) = objects.events(id)
    suspend fun recentPositions(id: String) = positions.recent(id)

    suspend fun search(query: String, date: LocalDate?): AeroLookup {
        val day = date ?: LocalDate.now()
        return when (val parsed = parseFlightQuery(query)) {
            is FlightQuery.Unknown -> AeroLookup(emptyList(), SearchReason.UNKNOWN_QUERY)
            is FlightQuery.Number -> providers.searchAeroNumber(keys.snapshot(), parsed.flightNumber, day, user = true)
            is FlightQuery.Route -> {
                val lookup = providers.searchAeroAirportDepartures(keys.snapshot(), parsed.from, day)
                if (lookup.reason != SearchReason.OK && lookup.reason != SearchReason.EMPTY) return lookup
                val filtered = lookup.flights.filter { it.toIata.equals(parsed.to, true) }
                if (filtered.isNotEmpty()) AeroLookup(filtered, SearchReason.OK)
                else {
                    val from = airports.get(parsed.from)
                    val to = airports.get(parsed.to)
                    if (from == null || to == null) AeroLookup(emptyList(), SearchReason.EMPTY)
                    else AeroLookup(
                        listOf(
                            FlightSearchResult(
                                flightNumber = "",
                                fromIata = from.iata,
                                toIata = to.iata,
                                fromCity = from.city,
                                toCity = to.city,
                                fromTimezone = from.timezone,
                                toTimezone = to.timezone,
                                fromLat = from.lat,
                                fromLon = from.lon,
                                toLat = to.lat,
                                toLon = to.lon,
                                status = FlightStatus.UNKNOWN,
                                source = "local",
                                timesEstimated = true,
                            ),
                        ),
                        SearchReason.OK,
                    )
                }
            }
        }
    }

    suspend fun saveSearch(
        result: FlightSearchResult,
        trackDaily: Boolean,
        inLogbook: Boolean,
        seat: String? = null,
        notes: String? = null,
    ): FlightEntity {
        val dep = result.scheduledDep ?: error("need departure")
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        result.fromIata?.let { upsertAirport(it, result.fromCity, result.fromTimezone, result.fromLat, result.fromLon) }
        result.toIata?.let { upsertAirport(it, result.toCity, result.toTimezone, result.toLat, result.toLon) }
        val entity = FlightEntity(
            id = id,
            flightNumber = result.flightNumber.uppercase().replace(Regex("\\s+"), ""),
            airlineName = result.airlineName,
            airlineIata = result.airlineIata,
            airlineIcao = result.airlineIcao,
            fromIata = result.fromIata,
            toIata = result.toIata,
            fromCity = result.fromCity,
            toCity = result.toCity,
            fromCountry = result.fromCountry ?: airports.get(result.fromIata.orEmpty())?.country,
            toCountry = result.toCountry ?: airports.get(result.toIata.orEmpty())?.country,
            fromTimezone = result.fromTimezone ?: airports.get(result.fromIata.orEmpty())?.timezone,
            toTimezone = result.toTimezone ?: airports.get(result.toIata.orEmpty())?.timezone,
            fromLat = result.fromLat ?: airports.get(result.fromIata.orEmpty())?.lat,
            fromLon = result.fromLon ?: airports.get(result.fromIata.orEmpty())?.lon,
            toLat = result.toLat ?: airports.get(result.toIata.orEmpty())?.lat,
            toLon = result.toLon ?: airports.get(result.toIata.orEmpty())?.lon,
            scheduledDep = dep,
            scheduledArr = result.scheduledArr,
            estimatedDep = result.estimatedDep,
            estimatedArr = result.estimatedArr,
            actualDep = result.actualDep,
            actualArr = result.actualArr,
            status = if (result.status == FlightStatus.UNKNOWN) FlightStatus.SCHEDULED else result.status,
            gate = result.gate,
            terminal = result.terminal,
            arrivalGate = result.arrivalGate,
            arrivalTerminal = result.arrivalTerminal,
            baggageBelt = result.baggageBelt,
            checkInDesk = result.checkInDesk,
            delayMinutes = result.delayMinutes,
            arrivalDelayMinutes = result.arrivalDelayMinutes,
            codeshares = result.codeshares,
            isCargo = result.isCargo,
            runwayDepAt = result.runwayDepAt,
            runwayArrAt = result.runwayArrAt,
            aircraftType = result.aircraftType,
            registration = result.registration,
            icao24 = result.icao24,
            callsign = result.callsign,
            lastLat = null,
            lastLon = null,
            lastAltitudeFt = null,
            lastVelocityKts = null,
            lastHeading = null,
            lastOnGround = null,
            lastPositionAt = null,
            lastSquawk = null,
            lastStatusSource = result.source,
            pollPhase = PollPhase.INACTIVE,
            nextPollAt = now,
            seat = seat,
            notes = notes,
            pushAlerts = true,
            trackDaily = trackDaily,
            inLogbook = if (trackDaily) false else inLogbook,
            createdAt = now,
            updatedAt = now,
        )
        val phase = resolvePollPhase(entity.toPollInput())
        flights.upsert(entity.copy(pollPhase = phase, nextPollAt = now))
        return entity
    }

    suspend fun updateMeta(id: String, seat: String?, notes: String?, pushAlerts: Boolean, trackDaily: Boolean, inLogbook: Boolean) {
        val row = flights.get(id) ?: return
        flights.upsert(row.copy(seat = seat, notes = notes, pushAlerts = pushAlerts, trackDaily = trackDaily, inLogbook = inLogbook, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) {
        positions.deleteForFlight(id)
        flights.delete(id)
    }

    suspend fun upsertAirport(
        iata: String,
        city: String?,
        tz: String?,
        lat: Double?,
        lon: Double?,
        icao: String? = null,
        name: String? = null,
        country: String? = null,
        elevationFt: Int? = null,
    ) {
        val existing = airports.get(iata)
        airports.upsert(
            AirportEntity(
                iata = iata,
                icao = icao ?: existing?.icao,
                name = name ?: existing?.name,
                city = city ?: existing?.city,
                country = country ?: existing?.country,
                timezone = tz ?: existing?.timezone,
                lat = lat ?: existing?.lat,
                lon = lon ?: existing?.lon,
                elevationFt = elevationFt ?: existing?.elevationFt,
            ),
        )
    }

    suspend fun savePosition(pos: PositionEntity) = positions.insert(pos)
    suspend fun upsertFlight(row: FlightEntity) = flights.upsert(row)

    /** Merge exported flights by id or flightNumber+scheduledDep. Returns how many rows were written. */
    suspend fun importBackupFlights(incoming: List<FlightEntity>): Int {
        val now = System.currentTimeMillis()
        var written = 0
        for (raw in incoming) {
            val existing = flights.getByNumberAndDep(raw.flightNumber, raw.scheduledDep)
                ?: flights.get(raw.id)
            val entity = raw.copy(
                id = existing?.id ?: raw.id,
                createdAt = existing?.createdAt ?: raw.createdAt,
                updatedAt = now,
                nextPollAt = now,
                pollPhase = resolvePollPhase(raw.toPollInput()),
            )
            flights.upsert(entity)
            entity.fromIata?.let {
                upsertAirport(it, entity.fromCity, entity.fromTimezone, entity.fromLat, entity.fromLon)
            }
            entity.toIata?.let {
                upsertAirport(it, entity.toCity, entity.toTimezone, entity.toLat, entity.toLon)
            }
            written++
        }
        return written
    }

    suspend fun insertAlert(alert: AlertEntity) {
        alerts.insert(alert)
        alerts.trim()
    }
    suspend fun markAlertRead(id: String) = alerts.markRead(id)
    suspend fun markAllAlertsRead() = alerts.markAllRead()

    suspend fun trackObject(icao24: String?, callsign: String, label: String?) {
        objects.upsert(
            TrackedObjectEntity(
                id = UUID.randomUUID().toString(),
                icao24 = icao24?.lowercase(),
                callsign = callsign.trim(),
                label = label,
                lastLat = null,
                lastLon = null,
                lastAltitudeFt = null,
                lastOnGround = null,
                lastSeenAt = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun untrackObject(id: String) = objects.delete(id)
    suspend fun upsertObject(row: TrackedObjectEntity) = objects.upsert(row)
    suspend fun insertObjectEvent(event: ObjectEventEntity) = objects.insertEvent(event)

    suspend fun listDaily() = flights.listDaily()
}

fun FlightEntity.toPollInput() = de.rolfwalker.flightbuddy.core.domain.PollInput(
    status = status,
    scheduledDep = scheduledDep,
    actualDep = actualDep,
    estimatedDep = estimatedDep,
    scheduledArr = scheduledArr,
    estimatedArr = estimatedArr,
    lastLat = lastLat,
    lastLon = lastLon,
    lastPositionAt = lastPositionAt,
    actualArr = actualArr,
    origin = if (fromLat != null && fromLon != null) de.rolfwalker.flightbuddy.core.model.LatLon(fromLat, fromLon) else null,
    dest = if (toLat != null && toLon != null) de.rolfwalker.flightbuddy.core.model.LatLon(toLat, toLon) else null,
)

fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
