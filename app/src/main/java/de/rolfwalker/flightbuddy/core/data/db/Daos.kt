package de.rolfwalker.flightbuddy.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface FlightDao {
    @Query("SELECT * FROM flights ORDER BY scheduledDep DESC")
    fun observeAll(): Flow<List<FlightEntity>>

    @Query("SELECT * FROM flights ORDER BY scheduledDep DESC")
    suspend fun listAll(): List<FlightEntity>

    @Query("SELECT * FROM flights WHERE id = :id")
    fun observeById(id: String): Flow<FlightEntity?>

    @Query("SELECT * FROM flights WHERE id = :id")
    suspend fun get(id: String): FlightEntity?

    @Query("SELECT * FROM flights WHERE flightNumber = :number AND scheduledDep = :scheduledDep LIMIT 1")
    suspend fun getByNumberAndDep(number: String, scheduledDep: Long): FlightEntity?

    @Query("SELECT * FROM flights WHERE status NOT IN (:terminal)")
    suspend fun listActive(terminal: List<FlightStatus> = listOf(FlightStatus.LANDED, FlightStatus.CANCELLED, FlightStatus.DIVERTED)): List<FlightEntity>

    @Query("SELECT * FROM flights WHERE trackDaily = 1")
    suspend fun listDaily(): List<FlightEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(flight: FlightEntity)

    @Update
    suspend fun update(flight: FlightEntity)

    @Query("DELETE FROM flights WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM flights WHERE flightNumber = :number AND fromIata = :from AND toIata = :to ORDER BY scheduledDep DESC")
    suspend fun listSeries(number: String, from: String?, to: String?): List<FlightEntity>
}

@Dao
interface PositionDao {
    @Insert
    suspend fun insert(position: PositionEntity)

    @Query("SELECT * FROM positions WHERE flightId = :flightId ORDER BY recordedAt DESC LIMIT :limit")
    suspend fun recent(flightId: String, limit: Int = 200): List<PositionEntity>

    @Query("DELETE FROM positions WHERE flightId = :flightId")
    suspend fun deleteForFlight(flightId: String)
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<AlertEntity>

    @Query("SELECT COUNT(*) FROM alerts WHERE read = 0")
    fun observeUnread(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertEntity)

    @Query("UPDATE alerts SET read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE alerts SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM alerts WHERE id NOT IN (SELECT id FROM alerts ORDER BY createdAt DESC LIMIT 50)")
    suspend fun trim()
}

@Dao
interface TrackedObjectDao {
    @Query("SELECT * FROM tracked_objects ORDER BY callsign")
    fun observeAll(): Flow<List<TrackedObjectEntity>>

    @Query("SELECT * FROM tracked_objects")
    suspend fun listAll(): List<TrackedObjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(obj: TrackedObjectEntity)

    @Query("DELETE FROM tracked_objects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM tracked_objects WHERE id = :id")
    suspend fun get(id: String): TrackedObjectEntity?

    @Insert
    suspend fun insertEvent(event: ObjectEventEntity)

    @Query("SELECT * FROM object_events WHERE objectId = :id ORDER BY at DESC LIMIT 80")
    suspend fun events(id: String): List<ObjectEventEntity>
}

@Dao
interface AirportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(airport: AirportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(airports: List<AirportEntity>)

    @Query("SELECT * FROM airports WHERE iata = :iata")
    suspend fun get(iata: String): AirportEntity?

    @Query("SELECT * FROM airports")
    suspend fun listAll(): List<AirportEntity>
}

@Dao
interface ApiLogDao {
    @Insert
    suspend fun insert(log: ApiLogEntity)

    @Query("SELECT * FROM api_logs WHERE provider = :provider ORDER BY at DESC LIMIT 1")
    suspend fun last(provider: String): ApiLogEntity?
}
