package de.rolfwalker.flightbuddy.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.airlineCodeForLogo
import de.rolfwalker.flightbuddy.core.domain.airlineInitials
import de.rolfwalker.flightbuddy.core.domain.airlineLogoUrl
import de.rolfwalker.flightbuddy.core.domain.isEmergencySquawk
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.domain.isPastStatus
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.ui.isLegDelayed
import de.rolfwalker.flightbuddy.core.ui.resolveLegTimes
import de.rolfwalker.flightbuddy.core.ui.status.FlightProgressMarker
import de.rolfwalker.flightbuddy.core.ui.status.airlineFlightWithFreshness
import de.rolfwalker.flightbuddy.core.ui.status.flightProgressPercent
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusLabel
import de.rolfwalker.flightbuddy.core.ui.status.progressMarkerDrawable
import de.rolfwalker.flightbuddy.tracking.LiveFlightNotification
import de.rolfwalker.flightbuddy.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

val WIDGET_FLIGHT_ID = stringPreferencesKey("flight_id")
val PARAM_FLIGHT_ID = ActionParameters.Key<String>("flight_id")

/** Tight top-aligned 4×2 / 5×2 — never vertically centered. */
private object WidgetPad {
    val horizontal = 8.dp
    val top = 16.dp
    val bottom = 4.dp
    val logoGap = 6.dp
    /** Logo 70dp, title ~40dp. Pull so the chip stays ~20dp under the title. */
    val titleToChipPull = (-10).dp
}

/** Header mark. Fixed 70dp square — never wrap to bitmap pixels. */
private object WidgetLogo {
    const val sizeDp = 70
}

/** Progress-line plane. Material flight glyph is rotated 90° CW in the drawable. */
internal object WidgetPlane {
    const val sizeDp = 24
}

private object WidgetColors {
    val background = ColorProvider(R.color.widget_bg)
    val primaryText = ColorProvider(R.color.widget_text)
    val secondaryText = ColorProvider(R.color.widget_text_muted)
    val accent = ColorProvider(R.color.widget_accent)
    val delay = ColorProvider(R.color.widget_delay)
    val error = ColorProvider(R.color.widget_error)
    val pill = ColorProvider(R.color.widget_pill)
    val pillDelay = ColorProvider(R.color.widget_pill_delay)
    val pillError = ColorProvider(R.color.widget_pill_error)
    val progressTrack = ColorProvider(R.color.widget_progress_track)
    val marker = ColorProvider(R.color.widget_marker)
}

internal object WidgetPins {
    private const val PREFS = "flight_widget_pins"

    fun save(context: Context, appWidgetId: Int, flightId: String) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(appWidgetId.toString(), flightId)
            .commit()
    }

    fun get(context: Context, appWidgetId: Int): String? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(appWidgetId.toString(), null)
    }

    fun remove(context: Context, appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(appWidgetId.toString())
            .apply()
    }
}

open class FlightBuddyWidget : GlanceAppWidget() {
    /** Single composition — Exact remasured on every tick and the host scaled the card. */
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrNull()
        try {
            val repo = object : KoinComponent { val r: FlightRepository by inject() }.r
            val flight = resolveWidgetFlight(context, id, repo)
            val iata = airlineCodeForLogo(flight?.airlineIata, flight?.flightNumber)
            val logo = runCatching { loadAirlineLogo(context, iata) }.getOrNull()
            val wide = appWidgetId?.let { isWideWidget(context, it) } ?: false
            val widthDp = appWidgetId?.let { widgetWidthDp(context, it) } ?: 250
            provideContent {
                WidgetBody(context, flight, iata, logo, wide, widthDp)
            }
        } catch (t: Throwable) {
            Log.e("FlightBuddy/Widget", "provideGlance failed", t)
        }
        // RemoteViews is the displayed card. Glance must not remain the last writer.
        if (appWidgetId != null) {
            runCatching { RemoteFlightWidget.update(context, appWidgetId) }
        }
    }
}

/** Same card as [FlightBuddyWidget], placed as a 5×2 target on 5-column launchers. */
class FlightBuddyWidget5x2 : FlightBuddyWidget()

/** First LIVE, else first upcoming, else first tracked. Null only when the list is empty. */
internal fun pickDefaultFlight(flights: List<FlightEntity>): FlightEntity? {
    if (flights.isEmpty()) return null
    flights.firstOrNull { isLiveStatus(it.status) }?.let { return it }
    flights.filter { !isLiveStatus(it.status) && !isPastStatus(it.status) }
        .minByOrNull { it.scheduledDep }
        ?.let { return it }
    return flights.first()
}

private suspend fun resolveWidgetFlight(
    context: Context,
    glanceId: GlanceId,
    repo: FlightRepository,
): FlightEntity? {
    val pinnedId = resolvePinnedFlightId(context, glanceId)
    pinnedId?.let { repo.getFlight(it) }?.let { return it }
    val chosen = pickDefaultFlight(repo.listFlights()) ?: return null
    persistPin(context, glanceId, chosen.id)
    return chosen
}

private suspend fun resolvePinnedFlightId(context: Context, glanceId: GlanceId): String? {
    val fromGlance = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)[WIDGET_FLIGHT_ID]
    if (!fromGlance.isNullOrBlank()) return fromGlance
    val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(glanceId) }.getOrNull()
        ?: return null
    val fromStore = WidgetPins.get(context, appWidgetId) ?: return null
    updateAppWidgetState(context, glanceId) { it[WIDGET_FLIGHT_ID] = fromStore }
    return fromStore
}

private suspend fun persistPin(context: Context, glanceId: GlanceId, flightId: String) {
    val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(glanceId) }.getOrNull()
    if (appWidgetId != null) WidgetPins.save(context, appWidgetId, flightId)
    updateAppWidgetState(context, glanceId) { it[WIDGET_FLIGHT_ID] = flightId }
}

private fun widgetOptions(context: Context, appWidgetId: Int): Bundle? =
    runCatching { AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId) }.getOrNull()

/** Same breakpoint as [RemoteFlightWidget] (min width ≥ 250). */
private fun isWideWidget(context: Context, appWidgetId: Int): Boolean =
    (widgetOptions(context, appWidgetId)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0) >= 250

private fun widgetWidthDp(context: Context, appWidgetId: Int): Int =
    (widgetOptions(context, appWidgetId)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 250)
        .coerceAtLeast(110)

@Composable
private fun WidgetBody(
    context: Context,
    flight: FlightEntity?,
    iata: String?,
    logo: Bitmap?,
    wide: Boolean,
    widthDp: Int,
) {
    val click = if (flight != null) {
        actionStartActivity<MainActivity>(actionParametersOf(PARAM_FLIGHT_ID to flight.id))
    } else {
        actionStartActivity<MainActivity>()
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.background)
            .padding(
                start = WidgetPad.horizontal,
                top = WidgetPad.top,
                end = WidgetPad.horizontal,
                bottom = WidgetPad.bottom,
            )
            .clickable(click),
        contentAlignment = Alignment.TopStart,
    ) {
        if (flight == null) {
            EmptyWidgetCard(context)
            return@Box
        }
        val model = widgetCardModel(
            context = context,
            f = flight,
            showFreshness = true,
            showWeekday = wide,
            showBaggage = wide,
        )
        Column(
            verticalAlignment = Alignment.Top,
            horizontalAlignment = Alignment.Start,
            modifier = GlanceModifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = GlanceModifier.fillMaxWidth(),
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    AirlineFlightHeader(
                        airlineFlight = model.airlineFlight,
                        freshness = model.freshness,
                        sideBySide = wide,
                    )
                    Text(
                        model.cityRoute,
                        style = TextStyle(
                            color = WidgetColors.primaryText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                }
                Spacer(GlanceModifier.width(WidgetPad.logoGap))
                LogoBox(iata, flight.airlineName, logo)
            }
            Column(
                modifier = GlanceModifier.fillMaxWidth().padding(top = WidgetPad.titleToChipPull),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = GlanceModifier.fillMaxWidth(),
                ) {
                    StatusPill(context, model.chip)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        model.countdown,
                        style = TextStyle(
                            color = statusColor(model.chip),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                RoutePlaneRow(
                    from = model.fromIata,
                    to = model.toIata,
                    fraction = model.progress / 100f,
                    showIata = model.showRouteIata,
                    marker = model.progressMarker,
                    widthDp = widthDp,
                )
                Spacer(GlanceModifier.height(2.dp))
                BigTimesRow(context, model)
                if (wide) {
                    model.weekday?.let { day ->
                        Text(
                            day,
                            style = TextStyle(color = WidgetColors.secondaryText, fontSize = 10.sp),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(GlanceModifier.height(2.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        model.depStand.orEmpty(),
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetColors.secondaryText, fontSize = 11.sp),
                        maxLines = 1,
                    )
                    Text(
                        model.arrStand.orEmpty(),
                        style = TextStyle(color = WidgetColors.secondaryText, fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutePlaneRow(
    from: String,
    to: String,
    fraction: Float,
    showIata: Boolean,
    marker: FlightProgressMarker,
    widthDp: Int,
) {
    val iataReserve = if (showIata) 72.dp else 16.dp
    val avail = (widthDp.dp - iataReserve).coerceAtLeast(40.dp)
    val markerAt = (avail * fraction.coerceIn(0f, 1f)).coerceAtLeast(0.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth(),
    ) {
        if (showIata) {
            Text(
                from,
                style = TextStyle(
                    color = WidgetColors.primaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(6.dp))
        }
        Box(
            modifier = GlanceModifier.defaultWeight().height(WidgetPlane.sizeDp.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .cornerRadius(2.dp)
                    .background(WidgetColors.progressTrack),
            ) {}
            if (fraction > 0f) {
                Box(
                    modifier = GlanceModifier
                        .width(markerAt.coerceAtLeast(4.dp))
                        .height(3.dp)
                        .cornerRadius(2.dp)
                        .background(WidgetColors.accent),
                ) {}
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(GlanceModifier.width(markerAt))
                Image(
                    provider = ImageProvider(progressMarkerDrawable(marker)),
                    contentDescription = "progress",
                    colorFilter = ColorFilter.tint(WidgetColors.marker),
                    modifier = GlanceModifier.size(WidgetPlane.sizeDp.dp),
                )
            }
        }
        if (showIata) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                to,
                style = TextStyle(
                    color = WidgetColors.primaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BigTimesRow(context: Context, model: WidgetCardModel) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                model.dep.effective,
                style = TextStyle(
                    color = if (model.dep.delayed) WidgetColors.delay else WidgetColors.primaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                scheduledUnder(context, model.dep.scheduled),
                style = TextStyle(color = WidgetColors.secondaryText, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = GlanceModifier.defaultWeight(),
        ) {
            Text(
                model.arr.effective,
                style = TextStyle(
                    color = if (model.arr.delayed) WidgetColors.delay else WidgetColors.primaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                scheduledUnder(context, model.arr.scheduled),
                style = TextStyle(color = WidgetColors.secondaryText, fontSize = 11.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyWidgetCard(context: Context) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            context.getString(R.string.widget_empty),
            style = TextStyle(
                color = WidgetColors.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun StatusPill(context: Context, status: FlightStatus) {
    val bg = when (status) {
        FlightStatus.DELAYED -> WidgetColors.pillDelay
        FlightStatus.CANCELLED, FlightStatus.DIVERTED -> WidgetColors.pillError
        else -> WidgetColors.pill
    }
    Text(
        statusLabel(context, status),
        modifier = GlanceModifier
            .background(bg)
            .cornerRadius(20.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = TextStyle(
            color = statusColor(status),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

@Composable
private fun AirlineFlightHeader(
    airlineFlight: String,
    freshness: String?,
    sideBySide: Boolean,
) {
    if (freshness.isNullOrBlank()) {
        Text(
            airlineFlight,
            style = TextStyle(
                color = WidgetColors.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        return
    }
    if (sideBySide) {
        // Glance has no spans — two Texts keep the parenthetical smaller/gray.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth(),
        ) {
            Text(
                airlineFlight,
                style = TextStyle(
                    color = WidgetColors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(
                " ($freshness)",
                style = TextStyle(color = WidgetColors.secondaryText, fontSize = 11.sp),
                maxLines = 1,
            )
        }
        return
    }
    Text(
        airlineFlightWithFreshness(airlineFlight, freshness),
        style = TextStyle(
            color = WidgetColors.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

@Composable
private fun LogoBox(
    iata: String?,
    name: String?,
    logo: Bitmap?,
) {
    val box = WidgetLogo.sizeDp
    val initials = airlineInitials(iata, name)
    Box(
        modifier = GlanceModifier.size(width = box.dp, height = box.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        if (logo != null) {
            Image(
                provider = ImageProvider(logo),
                contentDescription = name ?: iata ?: "logo",
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.size(width = box.dp, height = box.dp),
            )
        } else {
            Text(
                initials,
                style = TextStyle(
                    color = WidgetColors.accent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

private val logoMemory = ConcurrentHashMap<String, Bitmap>()

internal suspend fun loadAirlineLogo(context: Context, iata: String?): Bitmap? {
    val code = iata?.trim()?.uppercase().orEmpty()
    val url = airlineLogoUrl(code) ?: return null
    val cacheKey = "${code}_mark"
    logoMemory[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }
    return withContext(Dispatchers.IO) {
        logoMemory[cacheKey]?.takeIf { !it.isRecycled }?.let { return@withContext it }
        val disk = File(context.cacheDir, "airline_logos/${code}_mark.png")
        readLogoFile(disk)?.let { cached ->
            logoMemory[cacheKey] = cached
            return@withContext cached
        }
        val downloaded = downloadAirlineLogo(url) ?: return@withContext null
        logoMemory[cacheKey] = downloaded
        runCatching {
            disk.parentFile?.mkdirs()
            disk.outputStream().use { out -> downloaded.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }
        downloaded
    }
}

private fun readLogoFile(file: File): Bitmap? {
    if (!file.isFile) return null
    return runCatching {
        file.inputStream().use { BitmapFactory.decodeStream(it) }?.let { prepareGlanceBitmap(it) }
    }.getOrNull()
}

private fun downloadAirlineLogo(url: String): Bitmap? = runCatching {
    val conn = java.net.URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8_000
    conn.readTimeout = 8_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "FlightBuddy/1.0 (Android)")
    conn.setRequestProperty("Accept", "image/png,image/*;q=0.8")
    try {
        if (conn.responseCode !in 200..299) return@runCatching null
        conn.inputStream.use { stream ->
            BitmapFactory.decodeStream(stream)?.let { prepareGlanceBitmap(it) }
        }
    } finally {
        conn.disconnect()
    }
}.getOrNull()

private fun prepareGlanceBitmap(src: Bitmap): Bitmap {
    val max = 320
    val longest = maxOf(src.width, src.height).coerceAtLeast(1)
    val scaled = if (longest > max) {
        val factor = max.toFloat() / longest
        Bitmap.createScaledBitmap(
            src,
            (src.width * factor).toInt().coerceAtLeast(1),
            (src.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        src
    }
    return if (scaled.config == Bitmap.Config.ARGB_8888) {
        scaled
    } else {
        scaled.copy(Bitmap.Config.ARGB_8888, false) ?: scaled
    }
}

private fun statusColor(status: FlightStatus) = when (status) {
    FlightStatus.DELAYED -> WidgetColors.delay
    FlightStatus.CANCELLED, FlightStatus.DIVERTED -> WidgetColors.error
    else -> WidgetColors.accent
}

internal fun statusLabel(context: Context, status: FlightStatus): String =
    flightStatusLabel(context, status)

internal fun statusShort(context: Context, f: FlightEntity): String =
    statusLabel(context, displayStatus(f))

private fun hintText(
    context: Context,
    f: FlightEntity,
    shown: FlightStatus,
    showDelay: Boolean,
    skipLive: Boolean,
): String? {
    if (isEmergencySquawk(f.lastSquawk)) return context.getString(R.string.flight_squawk, f.lastSquawk!!)
    if (shown == FlightStatus.DIVERTED) return context.getString(R.string.status_diverted)
    if (shown == FlightStatus.CANCELLED) return context.getString(R.string.status_cancelled)
    if (showDelay && (f.delayMinutes ?: 0) > 0) return context.getString(R.string.status_delayed_by, f.delayMinutes!!)
    if (!skipLive && isLiveStatus(shown)) return context.getString(R.string.flight_live)
    return null
}

internal fun standLine(context: Context, f: FlightEntity): String? {
    val dep = listOfNotNull(
        f.terminal?.trim()?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.flight_terminal) + " " + it },
        f.gate?.trim()?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.flight_gate) + " " + it },
    )
    val arr = listOfNotNull(
        f.arrivalTerminal?.trim()?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.flight_terminal) + " " + it },
        f.arrivalGate?.trim()?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.flight_gate) + " " + it },
    )
    return when {
        dep.isNotEmpty() && arr.isNotEmpty() && dep != arr -> (dep + arr).joinToString(" → ")
        dep.isNotEmpty() -> dep.joinToString(" · ")
        arr.isNotEmpty() -> arr.joinToString(" · ")
        else -> null
    }
}

internal fun progressPercent(f: FlightEntity, now: Long = System.currentTimeMillis()): Int? =
    flightProgressPercent(f, now)

internal fun routeLine(f: FlightEntity): String {
    val from = f.fromIata?.trim().orEmpty().ifBlank { "––" }
    val to = f.toIata?.trim().orEmpty().ifBlank { "––" }
    return "$from → $to"
}

@Composable
private fun WidgetClock(
    time: String,
    timeColor: ColorProvider,
    timeSize: androidx.compose.ui.unit.TextUnit,
    offsetSize: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight = FontWeight.Normal,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            time,
            style = TextStyle(color = timeColor, fontSize = timeSize, fontWeight = weight),
            maxLines = 1,
        )
        if (time != "––") {
            Text(
                " ${DateTimeFmt.offsetLabel()}",
                style = TextStyle(color = WidgetColors.secondaryText, fontSize = offsetSize),
                maxLines = 1,
            )
        }
    }
}

internal fun clockOrDash(ms: Long?): String {
    if (ms == null) return "––"
    return DateTimeFmt.time(ms, DateTimeFmt.deviceZone())
}

internal data class DepClocks(
    val planned: String,
    val effective: String,
    val delayed: Boolean,
)

internal fun depClocks(f: FlightEntity): DepClocks {
    val times = resolveLegTimes(f.scheduledDep, f.estimatedDep, f.actualDep)
    val delayed = f.status == FlightStatus.DELAYED ||
        (f.delayMinutes ?: 0) > 0 ||
        isLegDelayed(times, f.status == FlightStatus.DELAYED)
    return DepClocks(
        planned = clockOrDash(times.planned),
        effective = clockOrDash(times.effective ?: times.planned),
        delayed = delayed,
    )
}

internal fun aircraftLine(f: FlightEntity): String? {
    val type = f.aircraftType?.trim()?.takeIf { it.isNotEmpty() }
    val reg = f.registration?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(type, reg).joinToString(" · ").takeIf { it.isNotEmpty() }
}

internal fun statusColorRes(status: FlightStatus): Int = chipColorRes(status)

/**
 * RemoteViews is the only paint on ticks. Glance provideGlance ends by
 * writing the same XML card so the two paths cannot fight.
 */
open class FlightWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlightBuddyWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        runCatching { super.onUpdate(context, appWidgetManager, appWidgetIds) }
            .onFailure { Log.e("FlightBuddy/Widget", "Glance onUpdate failed", it) }
        appWidgetIds.forEach { id ->
            runCatching { RemoteFlightWidget.update(context, appWidgetManager, id) }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        runCatching { super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions) }
            .onFailure { Log.e("FlightBuddy/Widget", "Glance options change failed", it) }
        runCatching { RemoteFlightWidget.update(context, appWidgetManager, appWidgetId) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPins.remove(context, it) }
        runCatching { super.onDeleted(context, appWidgetIds) }
    }
}

class FlightWidgetReceiver : FlightWidgetProvider() {
    override val glanceAppWidget = FlightBuddyWidget()
}

class FlightWidget5x2Receiver : FlightWidgetProvider() {
    override val glanceAppWidget = FlightBuddyWidget5x2()
}

class WidgetUpdater(
    private val context: Context,
    private val liveNotif: LiveFlightNotification,
) : KoinComponent {
    @Suppress("UNUSED_PARAMETER")
    suspend fun updateAll(changedFlightIds: Set<String> = emptySet()) {
        // Same XML card every tick — do not also call Glance updateAll (size/metrics fight).
        RemoteFlightWidget.updateAll(context)
        runCatching { liveNotif.publishUpdate() }
    }

    suspend fun pinFlight(glanceId: GlanceId, flightId: String, appWidgetId: Int? = null) {
        val widgetId = appWidgetId
            ?: runCatching { GlanceAppWidgetManager(context).getAppWidgetId(glanceId) }.getOrNull()
        if (widgetId != null) WidgetPins.save(context, widgetId, flightId)
        runCatching {
            updateAppWidgetState(context, glanceId) { it[WIDGET_FLIGHT_ID] = flightId }
        }
        if (widgetId != null) {
            RemoteFlightWidget.update(context, widgetId)
        }
    }

    /** Configure-time bind: [WidgetPins] + RemoteViews first; Glance id may not exist yet. */
    suspend fun bindFlight(appWidgetId: Int, flightId: String) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        WidgetPins.save(context, appWidgetId, flightId)
        RemoteFlightWidget.update(context, appWidgetId)
        val glanceId = runCatching {
            GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        }.getOrNull() ?: return
        runCatching {
            updateAppWidgetState(context, glanceId) { it[WIDGET_FLIGHT_ID] = flightId }
        }
        runCatching { FlightBuddyWidget().update(context, glanceId) }
        runCatching { FlightBuddyWidget5x2().update(context, glanceId) }
    }
}
