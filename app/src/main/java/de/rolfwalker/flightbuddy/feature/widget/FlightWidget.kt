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
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
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
import de.rolfwalker.flightbuddy.core.ui.status.flightProgressPercent
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusLabel
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
    val compactHorizontal = 8.dp
    val roomyHorizontal = 10.dp
    val top = 12.dp
    val bottom = 4.dp
    val logoGap = 6.dp
    val section = 2.dp
}

/** Header logo (Google Flights). Width follows aspect — never cropped. */
private object WidgetLogo {
    const val compactMaxHeight = 54
    const val roomyMaxHeight = 54
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
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        try {
            val repo = object : KoinComponent { val r: FlightRepository by inject() }.r
            val flight = resolveWidgetFlight(context, id, repo)
            val iata = airlineCodeForLogo(flight?.airlineIata, flight?.flightNumber)
            val logo = runCatching { loadAirlineLogo(context, iata) }.getOrNull()
            provideContent {
                WidgetBody(context, flight, iata, logo)
            }
        } catch (t: Throwable) {
            Log.e("FlightBuddy/Widget", "provideGlance failed", t)
            val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrNull()
            if (appWidgetId != null) {
                runCatching { RemoteFlightWidget.update(context, appWidgetId) }
            }
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

@Composable
private fun WidgetBody(context: Context, flight: FlightEntity?, iata: String?, logo: Bitmap?) {
    val size = LocalSize.current
    val narrow = size.width < 180.dp
    val short = size.height < 72.dp
    val compact = narrow || short
    val wide = size.width >= 280.dp
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
                start = if (compact) WidgetPad.compactHorizontal else WidgetPad.roomyHorizontal,
                top = WidgetPad.top,
                end = if (compact) WidgetPad.compactHorizontal else WidgetPad.roomyHorizontal,
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
            showFreshness = wide || size.height >= 120.dp,
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
                    Text(
                        model.airlineFlight,
                        style = TextStyle(
                            color = WidgetColors.primaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        model.cityRoute,
                        style = TextStyle(
                            color = WidgetColors.primaryText,
                            fontSize = if (compact) 15.sp else 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    model.freshness?.let { fresh ->
                        Text(
                            fresh,
                            style = TextStyle(color = WidgetColors.secondaryText, fontSize = 11.sp),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(GlanceModifier.width(WidgetPad.logoGap))
                LogoBox(iata, flight.airlineName, logo, WidgetLogo.compactMaxHeight)
            }
            Spacer(GlanceModifier.height(WidgetPad.section))
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
            Spacer(GlanceModifier.height(3.dp))
            RoutePlaneRow(model.fromIata, model.toIata, model.progress / 100f)
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
            if (!short) {
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
private fun RoutePlaneRow(from: String, to: String, fraction: Float) {
    val avail = (LocalSize.current.width - 72.dp).coerceAtLeast(40.dp)
    val planeAt = (avail * fraction.coerceIn(0f, 1f)).coerceAtLeast(0.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth(),
    ) {
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
                        .width(planeAt.coerceAtLeast(4.dp))
                        .height(3.dp)
                        .cornerRadius(2.dp)
                        .background(WidgetColors.accent),
                ) {}
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(GlanceModifier.width(planeAt))
                Image(
                    provider = ImageProvider(R.drawable.widget_plane),
                    contentDescription = "progress",
                    modifier = GlanceModifier.size(WidgetPlane.sizeDp.dp),
                )
            }
        }
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
private fun LogoBox(
    iata: String?,
    name: String?,
    logo: Bitmap?,
    maxHeightDp: Int,
) {
    val (boxW, boxH) = logoBoxDp(logo, maxHeightDp)
    val initials = airlineInitials(iata, name)
    Box(
        modifier = GlanceModifier.size(width = boxW.dp, height = boxH.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        if (logo != null) {
            Image(
                provider = ImageProvider(logo),
                contentDescription = name ?: iata ?: "logo",
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.size(width = boxW.dp, height = boxH.dp),
            )
        } else {
            Text(
                initials,
                style = TextStyle(
                    color = WidgetColors.accent,
                    fontSize = if (boxH >= 48) 18.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

/** Fit inside maxHeight; width follows aspect. Glance Image dies at 0px — never shrink below 24. */
private fun logoBoxDp(logo: Bitmap?, maxHeightDp: Int): Pair<Int, Int> {
    val maxW = (maxHeightDp * 2).coerceAtMost(160)
    if (logo == null) return maxHeightDp to maxHeightDp
    val bw = logo.width.coerceAtLeast(1)
    val bh = logo.height.coerceAtLeast(1)
    val aspect = bw.toFloat() / bh.toFloat()
    var height = maxHeightDp
    var width = (height * aspect).toInt().coerceAtLeast(1)
    if (width > maxW) {
        width = maxW
        height = (width / aspect).toInt().coerceAtLeast(1)
    }
    return width.coerceAtLeast(24) to height.coerceAtLeast(24)
}

private val logoMemory = ConcurrentHashMap<String, Bitmap>()

internal suspend fun loadAirlineLogo(context: Context, iata: String?): Bitmap? {
    val code = iata?.trim()?.uppercase().orEmpty()
    val url = airlineLogoUrl(code) ?: return null
    logoMemory[code]?.takeIf { !it.isRecycled }?.let { return it }
    return withContext(Dispatchers.IO) {
        logoMemory[code]?.takeIf { !it.isRecycled }?.let { return@withContext it }
        val disk = File(context.cacheDir, "airline_logos/$code.png")
        readLogoFile(disk)?.let { cached ->
            logoMemory[code] = cached
            return@withContext cached
        }
        val downloaded = downloadAirlineLogo(url) ?: return@withContext null
        logoMemory[code] = downloaded
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
    val max = 220
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
 * Glance first. RemoteViews full card is painted first so a pin crash
 * never leaves the launcher on an empty stub.
 */
open class FlightWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlightBuddyWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            runCatching { RemoteFlightWidget.update(context, appWidgetManager, id) }
        }
        runCatching { super.onUpdate(context, appWidgetManager, appWidgetIds) }
            .onFailure { Log.e("FlightBuddy/Widget", "Glance onUpdate failed", it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        runCatching { RemoteFlightWidget.update(context, appWidgetManager, appWidgetId) }
        runCatching { super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions) }
            .onFailure { Log.e("FlightBuddy/Widget", "Glance options change failed", it) }
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

class WidgetUpdater(private val context: Context) : KoinComponent {
    suspend fun updateAll(changedFlightIds: Set<String> = emptySet()) {
        RemoteFlightWidget.updateAll(context)
        val manager = GlanceAppWidgetManager(context)
        runCatching { updateClass(manager, FlightBuddyWidget(), FlightBuddyWidget::class.java, changedFlightIds) }
            .onFailure { Log.e("FlightBuddy/Widget", "Glance 4x2 update failed", it) }
        runCatching { updateClass(manager, FlightBuddyWidget5x2(), FlightBuddyWidget5x2::class.java, changedFlightIds) }
            .onFailure { Log.e("FlightBuddy/Widget", "Glance 5x2 update failed", it) }
    }

    private suspend fun updateClass(
        manager: GlanceAppWidgetManager,
        widget: GlanceAppWidget,
        clazz: Class<out GlanceAppWidget>,
        changedFlightIds: Set<String>,
    ) {
        manager.getGlanceIds(clazz).forEach { id ->
            val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
            val appWidgetId = runCatching { manager.getAppWidgetId(id) }.getOrNull()
            val pinned = state[WIDGET_FLIGHT_ID] ?: appWidgetId?.let { WidgetPins.get(context, it) }
            if (changedFlightIds.isEmpty() || pinned == null || pinned in changedFlightIds) {
                widget.update(context, id)
            }
        }
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
        updateAll()
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
