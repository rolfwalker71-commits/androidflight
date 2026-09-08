package de.rolfwalker.flightbuddy.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.PollPhase

@Entity(tableName = "flights", indices = [Index(value = ["flightNumber", "scheduledDep"], unique = true)])
data class FlightEntity(
    @PrimaryKey val id: String,
    val flightNumber: String,
    val airlineName: String?,
    val airlineIata: String?,
    val airlineIcao: String?,
    val fromIata: String?,
    val toIata: String?,
    val fromCity: String?,
    val toCity: String?,
    val fromCountry: String?,
    val toCountry: String?,
    val fromTimezone: String?,
    val toTimezone: String?,
    val fromLat: Double?,
    val fromLon: Double?,
    val toLat: Double?,
    val toLon: Double?,
    val scheduledDep: Long,
    val scheduledArr: Long?,
    val estimatedDep: Long?,
    val estimatedArr: Long?,
    val actualDep: Long?,
    val actualArr: Long?,
    val status: FlightStatus,
    val gate: String?,
    val terminal: String?,
    val arrivalGate: String?,
    val arrivalTerminal: String?,
    val baggageBelt: String? = null,
    val delayMinutes: Int?,
    val aircraftType: String?,
    val registration: String?,
    val icao24: String?,
    val callsign: String?,
    val lastLat: Double?,
    val lastLon: Double?,
    val lastAltitudeFt: Double?,
    val lastVelocityKts: Double?,
    val lastHeading: Double?,
    val lastOnGround: Boolean?,
    val lastPositionAt: Long?,
    val lastSquawk: String?,
    val lastStatusSource: String?,
    val pollPhase: PollPhase,
    val nextPollAt: Long?,
    val seat: String?,
    val notes: String?,
    val pushAlerts: Boolean,
    val trackDaily: Boolean,
    val inLogbook: Boolean,
    val reminderPreflightSent: Boolean = false,
    val reminderGateCloseSent: Boolean = false,
    val reminderArrivalSent: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flightId: String,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Double?,
    val velocityKts: Double?,
    val heading: Double?,
    val onGround: Boolean,
    val source: String,
    val recordedAt: Long,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val id: String,
    val flightId: String?,
    val objectId: String?,
    val kind: String,
    val title: String,
    val body: String,
    val event: String,
    val createdAt: Long,
    val read: Boolean,
)

@Entity(tableName = "tracked_objects")
data class TrackedObjectEntity(
    @PrimaryKey val id: String,
    val icao24: String?,
    val callsign: String,
    val label: String?,
    val lastLat: Double?,
    val lastLon: Double?,
    val lastAltitudeFt: Double?,
    val lastOnGround: Boolean?,
    val lastSeenAt: Long?,
    val starts: Int = 0,
    val landings: Int = 0,
    val createdAt: Long,
)

@Entity(tableName = "object_events")
data class ObjectEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectId: String,
    val kind: String,
    val at: Long,
    val lat: Double?,
    val lon: Double?,
)

@Entity(tableName = "airports")
data class AirportEntity(
    @PrimaryKey val iata: String,
    val icao: String?,
    val name: String?,
    val city: String?,
    val country: String?,
    val timezone: String?,
    val lat: Double?,
    val lon: Double?,
)

@Entity(tableName = "api_logs")
data class ApiLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val endpoint: String,
    val statusCode: Int?,
    val ok: Boolean,
    val error: String?,
    val remaining: Int?,
    val at: Long,
)
