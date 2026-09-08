package de.rolfwalker.flightbuddy.tracking

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.feature.widget.WidgetUpdater
import de.rolfwalker.flightbuddy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Always-on tracker. Runs as a data-sync foreground service whenever at least
 * one upcoming/live flight (or tracked object) exists. WorkManager is only the
 * watchdog — this loop is what actually polls, writes Room, notifies, and
 * refreshes widgets after every successful poll.
 */
class FlightTrackerService : Service() {
    private val engine: PollEngine by inject()
    private val alerts: AlertDispatcher by inject()
    private val widgets: WidgetUpdater by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alerts.ensureChannels()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flightbuddy:tracker").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (loop?.isActive != true) {
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, AlertDispatcher.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setContentTitle(getString(R.string.tracker_title))
            .setContentText(getString(R.string.tracker_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(AlertDispatcher.ID_TRACKING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(AlertDispatcher.ID_TRACKING, notification)
        }
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            if (!engine.shouldKeepTracking()) {
                stopSelf()
                return
            }
            try {
                wakeLock?.acquire(3 * 60_000L)
                engine.pollDueFlights()
                engine.pollTrackedObjects()
            } catch (_: Exception) {
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
            // Always repaint every pinned card so countdown / progress / chip stay live
            // even when this cycle had no API change for that flight.
            runCatching { widgets.updateAll() }
            delay(minOf(engine.nextWakeDelayMs(), WIDGET_TICK_MS))
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlightBuddy/Tracker"
        /** Recompute chip, countdown, and plane at least this often while tracking. */
        private const val WIDGET_TICK_MS = 45_000L

        fun start(context: Context) {
            val i = Intent(context, FlightTrackerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                // Widget / boot / background process start is not allowed to
                // promote an FGS. Swallow so the host process (and widgets) stay up.
                Log.w(TAG, "startForegroundService blocked", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlightTrackerService::class.java))
        }
    }
}

object TrackerController {
    fun sync(context: Context) {
        FlightTrackerService.start(context)
        TrackerWatchdogWorker.enqueue(context)
    }
}
