package de.rolfwalker.flightbuddy.tracking

import android.app.Notification
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.domain.airlineCodeForLogo
import de.rolfwalker.flightbuddy.core.domain.airlineInitials
import de.rolfwalker.flightbuddy.core.domain.isAirborneDisplay
import de.rolfwalker.flightbuddy.core.ui.status.displayFlightStatus
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusCardModel
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusLabel
import de.rolfwalker.flightbuddy.core.ui.status.scheduledUnder
import de.rolfwalker.flightbuddy.feature.widget.FlightWidgetReceiver
import de.rolfwalker.flightbuddy.feature.widget.WidgetPins
import de.rolfwalker.flightbuddy.feature.widget.chipBackgroundRes
import de.rolfwalker.flightbuddy.feature.widget.chipColorRes
import de.rolfwalker.flightbuddy.feature.widget.clockColorRes
import de.rolfwalker.flightbuddy.feature.widget.loadAirlineLogo
import de.rolfwalker.flightbuddy.ui.MainActivity
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicReference

/**
 * Ongoing shade card for the same LIVE flight the 4×2 widget would pin.
 * Rich RemoteViews only while the setting is on and a flight is airborne.
 */
class LiveFlightNotification(
    private val context: Context,
    private val repo: FlightRepository,
    private val prefs: PrefsStore,
    private val engine: PollEngine,
    private val alerts: AlertDispatcher,
) {
    private val lastRich = AtomicReference<Notification?>(null)

    fun minimalNotification(): Notification = buildMinimal()

    fun cachedOrMinimal(): Notification = lastRich.get() ?: buildMinimal()

    suspend fun buildForegroundNotification(): Notification {
        val rich = resolveRichFlight()?.let { flight ->
            runCatching { buildRich(flight) }.getOrNull()
        }
        return if (rich != null) {
            lastRich.set(rich)
            rich
        } else {
            lastRich.set(null)
            buildMinimal()
        }
    }

    suspend fun onToggleChanged() {
        publishUpdate()
        val enabled = prefs.flow.first().liveNotification
        val live = resolveLiveFlight()
        if (engine.shouldKeepTracking() || (enabled && live != null)) {
            TrackerController.sync(context)
        }
    }

    suspend fun publishUpdate() {
        alerts.ensureChannels()
        val enabled = prefs.flow.first().liveNotification
        val live = if (enabled) resolveLiveFlight() else null
        val keep = engine.shouldKeepTracking()
        val notification = when {
            live != null -> runCatching { buildRich(live) }.getOrElse { buildMinimal() }
            keep -> buildMinimal()
            else -> null
        }
        if (notification != null) {
            if (live != null) lastRich.set(notification) else lastRich.set(null)
            try {
                NotificationManagerCompat.from(context).notify(AlertDispatcher.ID_TRACKING, notification)
            } catch (_: SecurityException) {
            }
        } else if (!FlightTrackerService.isRunning) {
            lastRich.set(null)
            runCatching { NotificationManagerCompat.from(context).cancel(AlertDispatcher.ID_TRACKING) }
        }
    }

    suspend fun resolveLiveFlight(): FlightEntity? {
        val live = repo.listFlights()
            .filter { isAirborneDisplay(displayFlightStatus(it)) }
            .sortedBy { it.scheduledDep }
        if (live.isEmpty()) return null
        val mgr = AppWidgetManager.getInstance(context)
        val compactIds = mgr.getAppWidgetIds(ComponentName(context, FlightWidgetReceiver::class.java))
        for (id in compactIds) {
            val pin = WidgetPins.get(context, id) ?: continue
            live.firstOrNull { it.id == pin }?.let { return it }
        }
        return live.first()
    }

    private suspend fun resolveRichFlight(): FlightEntity? {
        if (!prefs.flow.first().liveNotification) return null
        return resolveLiveFlight()
    }

    private fun buildMinimal(): Notification {
        val open = activityIntent(null)
        return NotificationCompat.Builder(context, AlertDispatcher.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setColor(ContextCompat.getColor(context, R.color.widget_accent))
            .setContentTitle(context.getString(R.string.tracker_minimal_title))
            .setContentText(context.getString(R.string.tracker_minimal_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private suspend fun buildRich(flight: FlightEntity): Notification {
        val model = flightStatusCardModel(
            context = context,
            f = flight,
            showFreshness = true,
            showWeekday = false,
            showBaggage = false,
        )
        val chipWord = flightStatusLabel(context, model.chip)
        val iata = airlineCodeForLogo(flight.airlineIata, flight.flightNumber)
        val logo = runCatching { loadAirlineLogo(context, iata) }.getOrNull()

        val collapsed = RemoteViews(context.packageName, R.layout.notification_live_collapsed)
        collapsed.setTextViewText(R.id.notif_collapsed_title, model.airlineFlight)
        collapsed.setTextViewText(R.id.notif_collapsed_route, model.cityRoute)
        collapsed.setTextViewText(R.id.notif_collapsed_chip, chipWord)
        collapsed.setTextColor(
            R.id.notif_collapsed_chip,
            ContextCompat.getColor(context, chipColorRes(model.chip)),
        )
        collapsed.setInt(R.id.notif_collapsed_chip, "setBackgroundResource", chipBackgroundRes(model.chip))
        collapsed.setTextViewText(R.id.notif_collapsed_countdown, model.countdown)
        collapsed.setTextColor(
            R.id.notif_collapsed_countdown,
            ContextCompat.getColor(context, chipColorRes(model.chip)),
        )

        val expanded = RemoteViews(context.packageName, R.layout.notification_live_expanded)
        expanded.setTextViewText(R.id.notif_airline_flight, model.airlineFlight)
        expanded.setTextViewText(R.id.notif_city_route, model.cityRoute)
        bindOptional(expanded, R.id.notif_freshness, model.freshness)
        bindLogo(expanded, flight, iata, logo)

        expanded.setTextViewText(R.id.notif_status_pill, chipWord)
        expanded.setTextColor(
            R.id.notif_status_pill,
            ContextCompat.getColor(context, chipColorRes(model.chip)),
        )
        expanded.setInt(R.id.notif_status_pill, "setBackgroundResource", chipBackgroundRes(model.chip))
        expanded.setTextViewText(R.id.notif_countdown, model.countdown)
        expanded.setTextColor(
            R.id.notif_countdown,
            ContextCompat.getColor(context, chipColorRes(model.chip)),
        )

        expanded.setViewVisibility(R.id.notif_progress_row, View.VISIBLE)
        expanded.setTextViewText(R.id.notif_from_iata, model.fromIata)
        expanded.setTextViewText(R.id.notif_to_iata, model.toIata)
        expanded.setProgressBar(R.id.notif_progress, 100, model.progress, false)

        expanded.setTextViewText(R.id.notif_dep_time, model.dep.effective)
        expanded.setTextColor(
            R.id.notif_dep_time,
            ContextCompat.getColor(context, clockColorRes(model.dep.delayed)),
        )
        expanded.setTextViewText(R.id.notif_dep_scheduled, scheduledUnder(context, model.dep.scheduled))
        expanded.setTextViewText(R.id.notif_arr_time, model.arr.effective)
        expanded.setTextColor(
            R.id.notif_arr_time,
            ContextCompat.getColor(context, clockColorRes(model.arr.delayed)),
        )
        expanded.setTextViewText(R.id.notif_arr_scheduled, scheduledUnder(context, model.arr.scheduled))

        val stands = listOfNotNull(model.depStand, model.arrStand).joinToString(" · ")
        bindOptional(expanded, R.id.notif_stands, stands.takeIf { it.isNotBlank() })

        val open = activityIntent(flight.id)
        return NotificationCompat.Builder(context, AlertDispatcher.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setColor(ContextCompat.getColor(context, R.color.widget_accent))
            .setContentTitle(model.airlineFlight)
            .setContentText("$chipWord · ${model.countdown}")
            .setSubText(model.cityRoute)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setProgress(100, model.progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun bindLogo(
        views: RemoteViews,
        flight: FlightEntity,
        iata: String?,
        logo: Bitmap?,
    ) {
        if (logo != null) {
            views.setImageViewBitmap(R.id.notif_logo, scaleLogo(logo))
            views.setViewVisibility(R.id.notif_logo, View.VISIBLE)
            views.setViewVisibility(R.id.notif_logo_fallback, View.GONE)
        } else {
            views.setViewVisibility(R.id.notif_logo, View.GONE)
            views.setViewVisibility(R.id.notif_logo_fallback, View.VISIBLE)
            views.setTextViewText(
                R.id.notif_logo_fallback,
                airlineInitials(iata, flight.airlineName),
            )
        }
    }

    private fun scaleLogo(src: Bitmap): Bitmap {
        val maxPx = (LOGO_MAX_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bw = src.width.coerceAtLeast(1)
        val bh = src.height.coerceAtLeast(1)
        val scale = minOf(maxPx.toFloat() / bw, maxPx.toFloat() / bh)
        val w = (bw * scale).toInt().coerceAtLeast(1)
        val h = (bh * scale).toInt().coerceAtLeast(1)
        if (src.width == w && src.height == h) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun bindOptional(views: RemoteViews, id: Int, text: String?) {
        if (text.isNullOrBlank()) {
            views.setViewVisibility(id, View.GONE)
        } else {
            views.setViewVisibility(id, View.VISIBLE)
            views.setTextViewText(id, text)
        }
    }

    private fun activityIntent(flightId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (flightId != null) putExtra(MainActivity.EXTRA_FLIGHT_ID, flightId)
        }
        return PendingIntent.getActivity(
            context,
            flightId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val LOGO_MAX_DP = 36
    }
}
