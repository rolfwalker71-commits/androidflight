package de.rolfwalker.flightbuddy.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.airlineCodeForLogo
import de.rolfwalker.flightbuddy.core.domain.airlineInitials
import de.rolfwalker.flightbuddy.core.ui.status.progressMarkerDrawable
import de.rolfwalker.flightbuddy.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

/**
 * Full-card RemoteViews path used as pin-safe initial paint and Glance fallback.
 * Google Flights status layout — not a two-line stub.
 */
internal object RemoteFlightWidget {
    private const val TAG = "FlightBuddy/Widget"
    private const val LOGO_MAX_DP = 70

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        receiverIds(context, mgr).forEach { update(context, mgr, it) }
    }

    fun update(context: Context, appWidgetId: Int) {
        update(context, AppWidgetManager.getInstance(context), appWidgetId)
    }

    fun update(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
        val views = runCatching { buildViews(context, mgr, appWidgetId) }
            .getOrElse { e ->
                Log.e(TAG, "buildViews failed for $appWidgetId", e)
                emptyCard(context, appWidgetId)
            }
        runCatching { mgr.updateAppWidget(appWidgetId, views) }
            .onFailure { Log.e(TAG, "updateAppWidget $appWidgetId failed", it) }
    }

    fun receiverIds(
        context: Context,
        mgr: AppWidgetManager = AppWidgetManager.getInstance(context),
    ): IntArray {
        val compact = mgr.getAppWidgetIds(ComponentName(context, FlightWidgetReceiver::class.java))
        val wide = mgr.getAppWidgetIds(ComponentName(context, FlightWidget5x2Receiver::class.java))
        return compact + wide
    }

    private fun buildViews(context: Context, mgr: AppWidgetManager, appWidgetId: Int): RemoteViews {
        val flight = resolveFlight(context, appWidgetId)
        val views = RemoteViews(context.packageName, R.layout.widget_flight)
        bindClick(context, views, appWidgetId, flight?.id)
        if (flight == null) {
            views.setViewVisibility(R.id.widget_content, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            return views
        }
        views.setViewVisibility(R.id.widget_content, View.VISIBLE)
        views.setViewVisibility(R.id.widget_empty, View.GONE)

        val options = runCatching { mgr.getAppWidgetOptions(appWidgetId) }.getOrNull()
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val wide = minW >= 250

        val model = widgetCardModel(
            context = context,
            f = flight,
            showFreshness = true,
            showWeekday = wide,
            showBaggage = wide,
        )

        val iata = airlineCodeForLogo(flight.airlineIata, flight.flightNumber)
        val logo = runCatching { runBlocking { loadAirlineLogo(context, iata) } }.getOrNull()
        bindLogo(context, views, flight, iata, logo)

        views.setTextViewText(
            R.id.widget_airline_flight,
            airlineFlightHeaderSpanned(context, model.airlineFlight, model.freshness),
        )
        views.setTextViewText(R.id.widget_city_route, model.cityRoute)

        views.setTextViewText(R.id.widget_status_pill, statusLabel(context, model.chip))
        views.setTextColor(
            R.id.widget_status_pill,
            ContextCompat.getColor(context, chipColorRes(model.chip)),
        )
        views.setInt(R.id.widget_status_pill, "setBackgroundResource", chipBackgroundRes(model.chip))
        views.setTextViewText(R.id.widget_countdown, model.countdown)
        views.setTextColor(
            R.id.widget_countdown,
            ContextCompat.getColor(context, chipColorRes(model.chip)),
        )

        if (model.showRouteIata) {
            views.setViewVisibility(R.id.widget_from_iata, View.VISIBLE)
            views.setViewVisibility(R.id.widget_to_iata, View.VISIBLE)
            views.setTextViewText(R.id.widget_from_iata, model.fromIata)
            views.setTextViewText(R.id.widget_to_iata, model.toIata)
        } else {
            views.setViewVisibility(R.id.widget_from_iata, View.GONE)
            views.setViewVisibility(R.id.widget_to_iata, View.GONE)
        }
        views.setImageViewResource(R.id.widget_plane, progressMarkerDrawable(model.progressMarker))
        views.setInt(R.id.widget_plane, "setColorFilter", widgetMarkerColor(context))
        views.setProgressBar(R.id.widget_progress, 100, model.progress, false)
        positionMarker(context, views, minW, model.progress, model.showRouteIata)

        views.setTextViewText(R.id.widget_dep_time, model.dep.effective)
        views.setTextColor(
            R.id.widget_dep_time,
            ContextCompat.getColor(context, clockColorRes(model.dep.delayed)),
        )
        views.setTextViewText(R.id.widget_dep_scheduled, scheduledUnder(context, model.dep.scheduled))

        views.setTextViewText(R.id.widget_arr_time, model.arr.effective)
        views.setTextColor(
            R.id.widget_arr_time,
            ContextCompat.getColor(context, clockColorRes(model.arr.delayed)),
        )
        views.setTextViewText(R.id.widget_arr_scheduled, scheduledUnder(context, model.arr.scheduled))

        bindOptionalLine(views, R.id.widget_weekday, model.weekday)
        bindOptionalLine(views, R.id.widget_dep_stand, model.depStand)
        bindOptionalLine(views, R.id.widget_arr_stand, model.arrStand)

        Log.i(TAG, "bound Google Flights card $appWidgetId to ${flight.flightNumber}")
        return views
    }

    private fun positionMarker(
        context: Context,
        views: RemoteViews,
        minWidthDp: Int,
        percent: Int,
        showIata: Boolean,
    ) {
        val density = context.resources.displayMetrics.density
        val iataReserve = if (showIata) 28 + 28 else 0
        val trackDp = (minWidthDp - 16 - iataReserve).coerceAtLeast(48)
        val leftDp = (((trackDp - WidgetPlane.sizeDp) * percent) / 100).coerceAtLeast(0)
        views.setViewPadding(R.id.widget_plane, (leftDp * density).toInt(), 0, 0, 0)
    }

    private fun bindLogo(
        context: Context,
        views: RemoteViews,
        flight: FlightEntity,
        iata: String?,
        logo: Bitmap?,
    ) {
        if (logo != null) {
            views.setImageViewBitmap(R.id.widget_logo, scaleLogo(context, logo))
            views.setViewVisibility(R.id.widget_logo, View.VISIBLE)
            views.setViewVisibility(R.id.widget_logo_fallback, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_logo, View.GONE)
            views.setViewVisibility(R.id.widget_logo_fallback, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_logo_fallback,
                airlineInitials(iata, flight.airlineName),
            )
        }
    }

    /** Always decode into the 70dp square so ImageView intrinsic size cannot blow the row. */
    private fun scaleLogo(context: Context, src: Bitmap): Bitmap {
        val density = context.resources.displayMetrics.density
        val max = (LOGO_MAX_DP * density).toInt().coerceAtLeast(24)
        val bw = src.width.coerceAtLeast(1)
        val bh = src.height.coerceAtLeast(1)
        val scale = minOf(max.toFloat() / bw, max.toFloat() / bh)
        val w = (bw * scale).toInt().coerceAtLeast(1)
        val h = (bh * scale).toInt().coerceAtLeast(1)
        if (src.width == w && src.height == h) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun airlineFlightHeaderSpanned(
        context: Context,
        airlineFlight: String,
        freshness: String?,
    ): CharSequence {
        if (freshness.isNullOrBlank()) return airlineFlight
        val suffix = " ($freshness)"
        val full = airlineFlight + suffix
        val start = airlineFlight.length
        return SpannableString(full).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.widget_text_muted)),
                start,
                full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                AbsoluteSizeSpan(11, true),
                start,
                full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun bindOptionalLine(views: RemoteViews, id: Int, text: String?) {
        if (text.isNullOrBlank()) {
            views.setViewVisibility(id, View.GONE)
        } else {
            views.setViewVisibility(id, View.VISIBLE)
            views.setTextViewText(id, text)
        }
    }

    private fun resolveFlight(context: Context, appWidgetId: Int): FlightEntity? {
        val repo = runCatching {
            GlobalContext.get().get<FlightRepository>()
        }.getOrElse { e ->
            Log.e(TAG, "FlightRepository unavailable", e)
            return null
        }
        return runBlocking {
            val pinned = WidgetPins.get(context, appWidgetId)
            pinned?.let { repo.getFlight(it) }?.let { return@runBlocking it }
            val chosen = pickDefaultFlight(repo.listFlights()) ?: return@runBlocking null
            WidgetPins.save(context, appWidgetId, chosen.id)
            chosen
        }
    }

    private fun emptyCard(context: Context, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_flight)
        views.setViewVisibility(R.id.widget_content, View.GONE)
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        bindClick(context, views, appWidgetId, null)
        return views
    }

    private fun bindClick(context: Context, views: RemoteViews, appWidgetId: Int, flightId: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (flightId != null) putExtra(MainActivity.EXTRA_FLIGHT_ID, flightId)
        }
        val pi = PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)
    }
}
