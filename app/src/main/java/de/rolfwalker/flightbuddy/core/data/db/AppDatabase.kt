package de.rolfwalker.flightbuddy.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.PollPhase

class Converters {
    @TypeConverter fun statusToString(v: FlightStatus) = v.name
    @TypeConverter fun stringToStatus(v: String) = runCatching { FlightStatus.valueOf(v) }.getOrDefault(FlightStatus.UNKNOWN)
    @TypeConverter fun phaseToString(v: PollPhase) = v.name
    @TypeConverter fun stringToPhase(v: String) = runCatching { PollPhase.valueOf(v) }.getOrDefault(PollPhase.INACTIVE)
}

@Database(
    entities = [
        FlightEntity::class,
        PositionEntity::class,
        AlertEntity::class,
        TrackedObjectEntity::class,
        ObjectEventEntity::class,
        AirportEntity::class,
        ApiLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flights(): FlightDao
    abstract fun positions(): PositionDao
    abstract fun alerts(): AlertDao
    abstract fun objects(): TrackedObjectDao
    abstract fun airports(): AirportDao
    abstract fun apiLogs(): ApiLogDao
}
