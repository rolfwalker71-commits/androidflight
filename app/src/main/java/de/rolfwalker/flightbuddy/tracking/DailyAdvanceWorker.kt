package de.rolfwalker.flightbuddy.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.domain.isTerminalStatus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class DailyAdvanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val repo: FlightRepository by inject()

    override suspend fun doWork(): Result {
        val daily = repo.listDaily()
        val seen = mutableSetOf<String>()
        for (row in daily) {
            if (!isTerminalStatus(row.status)) continue
            val key = "${row.flightNumber}|${row.fromIata}|${row.toIata}"
            if (!seen.add(key)) continue
            val tomorrow = LocalDate.now().plusDays(1)
            val lookup = repo.search("${row.flightNumber}", tomorrow)
            val match = lookup.flights.firstOrNull {
                it.fromIata == row.fromIata && it.toIata == row.toIata && it.scheduledDep != null
            } ?: continue
            val existing = repo.listFlights().any { it.flightNumber == match.flightNumber && it.scheduledDep == match.scheduledDep }
            if (!existing) {
                repo.saveSearch(match, trackDaily = true, inLogbook = false, seat = row.seat, notes = row.notes)
            }
        }
        TrackerController.sync(applicationContext)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<DailyAdvanceWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-advance", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
