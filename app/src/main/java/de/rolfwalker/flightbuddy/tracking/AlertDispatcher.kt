package de.rolfwalker.flightbuddy.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.AlertEntity
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.isEmergencySquawk
import de.rolfwalker.flightbuddy.core.domain.shouldAlertEmergencySquawk
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.ui.MainActivity
import java.util.UUID

class AlertDispatcher(
    private val context: Context,
    private val repo: FlightRepository,
) {
    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_EMERGENCY = "emergency"
        const val ID_TRACKING = 1001
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKING, context.getString(R.string.channel_tracking), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_tracking_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EMERGENCY, context.getString(R.string.channel_emergency), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
    }

    suspend fun dispatchFlightChanges(prev: FlightEntity, next: FlightEntity, prefs: UserPrefs) {
        if (!next.pushAlerts) return
        val code = displayFlightNumber(next.flightNumber)
        if (prefs.gateChanges && !next.gate.isNullOrBlank() && next.gate != prev.gate) {
            push(
                kind = "gate",
                title = context.getString(R.string.push_gate_title, code),
                body = context.getString(R.string.push_gate_body, next.gate, next.terminal.orEmpty()),
                event = context.getString(R.string.alerts_event_gate),
                flightId = next.id,
                high = false,
            )
        }
        val destChanged = next.toIata != null && prev.toIata != null && next.toIata != prev.toIata
        if (prefs.delaysStatus && destChanged && next.status != FlightStatus.CANCELLED) {
            push(
                kind = "status",
                title = context.getString(R.string.push_status_title, code, context.getString(R.string.status_diverted)),
                body = context.getString(R.string.push_status_body, "${prev.toIata} → ${next.toIata}"),
                event = context.getString(R.string.status_diverted),
                flightId = next.id,
                high = true,
            )
        } else if (prefs.delaysStatus && next.status != prev.status) {
            val label = statusLabel(next.status, next.delayMinutes)
            val high = next.status == FlightStatus.CANCELLED || next.status == FlightStatus.DIVERTED
            val body = if (next.status == FlightStatus.DELAYED && next.delayMinutes != null) {
                context.getString(R.string.push_delayed_body, next.delayMinutes)
            } else context.getString(R.string.push_status_body, label)
            push(
                kind = "status",
                title = context.getString(R.string.push_status_title, code, label),
                body = body,
                event = label,
                flightId = next.id,
                high = high,
            )
        } else if (prefs.delaysStatus && next.delayMinutes != null && next.delayMinutes != prev.delayMinutes && (next.delayMinutes ?: 0) > 0) {
            push(
                kind = "status",
                title = context.getString(R.string.push_status_title, code, context.getString(R.string.status_delayed)),
                body = context.getString(R.string.push_delayed_body, next.delayMinutes),
                event = context.getString(R.string.status_delayed),
                flightId = next.id,
                high = false,
            )
        }
        if (prefs.squawkAlerts && shouldAlertEmergencySquawk(prev.lastSquawk, next.lastSquawk) && isEmergencySquawk(next.lastSquawk)) {
            val meaning = when (next.lastSquawk) {
                "7500" -> context.getString(R.string.squawk_hijack)
                "7600" -> context.getString(R.string.squawk_radio)
                else -> context.getString(R.string.squawk_emergency)
            }
            push(
                kind = "squawk",
                title = context.getString(R.string.push_squawk_title, code, next.lastSquawk),
                body = context.getString(R.string.push_squawk_body, next.lastSquawk, meaning),
                event = context.getString(R.string.alerts_event_squawk, next.lastSquawk, meaning),
                flightId = next.id,
                high = true,
            )
        }
    }

    suspend fun dispatchTimeReminders(flight: FlightEntity, prefs: UserPrefs) {
        if (!flight.pushAlerts) return
        val now = System.currentTimeMillis()
        val dep = flight.estimatedDep ?: flight.scheduledDep
        val arr = flight.estimatedArr ?: flight.scheduledArr
        val code = displayFlightNumber(flight.flightNumber)
        var updated = flight
        if (prefs.preflight2h && !flight.reminderPreflightSent && dep - now in 0..(2 * 60 * 60 * 1000 + 60_000)) {
            push("preflight", context.getString(R.string.push_preflight_title, code), context.getString(R.string.push_preflight_body, flight.fromIata.orEmpty()), context.getString(R.string.alerts_event_preflight), flight.id, false)
            updated = updated.copy(reminderPreflightSent = true)
        }
        if (prefs.gateClose && !flight.reminderGateCloseSent && dep - now in 0..(45 * 60 * 1000 + 60_000)) {
            push("gate_close", context.getString(R.string.push_gate_close_title, code), context.getString(R.string.push_gate_close_body), context.getString(R.string.alerts_event_gate_close), flight.id, false)
            updated = updated.copy(reminderGateCloseSent = true)
        }
        if (prefs.arrivalSoon && arr != null && !flight.reminderArrivalSent && arr - now in 0..(30 * 60 * 1000 + 60_000)) {
            push("arrival_soon", context.getString(R.string.push_arrival_title, code), context.getString(R.string.push_arrival_body), context.getString(R.string.alerts_event_arrival), flight.id, false)
            updated = updated.copy(reminderArrivalSent = true)
        }
        if (updated !== flight) repo.upsertFlight(updated)
    }

    suspend fun notifyObject(callsign: String, airborne: Boolean) {
        val title = if (airborne) context.getString(R.string.push_object_air_title, callsign)
        else context.getString(R.string.push_object_land_title, callsign)
        val event = if (airborne) context.getString(R.string.alerts_event_object_air) else context.getString(R.string.alerts_event_object_land)
        push("object", title, event, event, null, false)
    }

    suspend fun notifyObjectSquawk(callsign: String, code: String) {
        push("squawk", context.getString(R.string.push_object_squawk_title, callsign, code), code, code, null, true)
    }

    private suspend fun push(
        kind: String,
        title: String,
        body: String,
        event: String,
        flightId: String?,
        high: Boolean,
    ) {
        val id = UUID.randomUUID().toString()
        repo.insertAlert(
            AlertEntity(
                id = id,
                flightId = flightId,
                objectId = null,
                kind = kind,
                title = title,
                body = body,
                event = event,
                createdAt = System.currentTimeMillis(),
                read = false,
            ),
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (flightId != null) putExtra(MainActivity.EXTRA_FLIGHT_ID, flightId)
            else putExtra(MainActivity.EXTRA_OPEN_ALERTS, true)
        }
        val pi = PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, if (high) CHANNEL_EMERGENCY else CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    private fun statusLabel(status: FlightStatus, delay: Int?): String = when (status) {
        FlightStatus.EN_ROUTE -> context.getString(R.string.status_en_route)
        FlightStatus.SCHEDULED -> context.getString(R.string.status_scheduled)
        FlightStatus.DELAYED -> if (delay != null) context.getString(R.string.status_delayed_by, delay) else context.getString(R.string.status_delayed)
        FlightStatus.BOARDING -> context.getString(R.string.status_boarding)
        FlightStatus.GATE_CLOSED -> context.getString(R.string.status_gate_closed)
        FlightStatus.DEPARTED -> context.getString(R.string.status_departed)
        FlightStatus.LANDED -> context.getString(R.string.status_landed)
        FlightStatus.CANCELLED -> context.getString(R.string.status_cancelled)
        FlightStatus.DIVERTED -> context.getString(R.string.status_diverted)
        FlightStatus.UNKNOWN -> context.getString(R.string.status_unknown)
    }
}
