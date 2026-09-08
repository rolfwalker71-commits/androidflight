package de.rolfwalker.flightbuddy.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.flightProgress
import de.rolfwalker.flightbuddy.core.domain.isEmergencySquawk
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.domain.isPastStatus
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.ui.isLegDelayed
import de.rolfwalker.flightbuddy.core.ui.resolveLegTimes
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

/** Vertical density for the 4×2 card — keep the block high, not centered. */
private object WidgetPad {
    val compactHorizontal = 8.dp
    val roomyHorizontal = 10.dp
    val top = 6.dp
    val bottom = 6.dp
    val afterHeaderCompact = 0.dp
    val afterHeader = 1.dp
    val afterTitleCompact = 1.dp
    val afterTitle = 2.dp
    val afterDate = 1.dp
    val beforeBarCompact = 2.dp
    val beforeBar = 3.dp
    val section = 2.dp
    val hint = 2.dp
}

private object WidgetColors {
    val background = ColorProvider(R.color.widget_bg)
    val primaryText = ColorProvider(R.color.widget_text)
    val secondaryText = ColorProvider(R.color.widget_text_muted)
    val accent = ColorProvider(R.color.widget_accent)
    val error = ColorProvider(R.color.widget_error)
    val pill = ColorProvider(R.color.widget_pill)
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
}

class FlightBuddyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = object : KoinComponent { val r: FlightRepository by inject() }.r
        val flight = resolveWidgetFlight(context, id, repo)
        val iata = airlineCodeForLogo(flight?.airlineIata, flight?.flightNumber)
        val logo = loadAirlineLogo(context, iata)
        provideContent {
            WidgetBody(context, flight, iata, logo)
        }
    }
}

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
    val roomy = !narrow && size.height >= 100.dp
    val extra = roomy && size.height >= 130.dp
    val numberSize = if (compact) 14.sp else 16.sp
    val click = if (flight != null) {
        actionStartActivity<MainActivity>(actionParametersOf(PARAM_FLIGHT_ID to flight.id))
    } else {
        actionStartActivity<MainActivity>()
    }
    Column(
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
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        if (flight == null) {
            EmptyWidgetCard(context)
            return@Column
        }
        Row(
            verticalAlignment = Alignment.Top,
            modifier = GlanceModifier.fillMaxWidth(),
        ) {
            LogoBox(iata, flight.airlineName, logo, if (compact) 52 else 72)
            Spacer(GlanceModifier.defaultWeight())
            StatusPill(context, flight)
        }
        Spacer(GlanceModifier.height(if (compact) WidgetPad.afterHeaderCompact else WidgetPad.afterHeader))
        Text(
            headerLine(flight),
            style = TextStyle(
                color = WidgetColors.primaryText,
                fontSize = numberSize,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(if (compact) WidgetPad.afterTitleCompact else WidgetPad.afterTitle))
        if (roomy) {
            Text(
                DateTimeFmt.date(flight.scheduledDep, DateTimeFmt.deviceZone()),
                style = TextStyle(color = WidgetColors.secondaryText, fontSize = 12.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(WidgetPad.afterDate))
        }
        val clocks = depClocks(flight)
        if (compact) {
            CompactDepTimes(context, clocks)
        } else {
            RoomyDepTimes(context, clocks)
        }
        val now = System.currentTimeMillis()
        val bar = resolveWidgetBar(
            status = flight.status,
            scheduledDep = flight.scheduledDep,
            estimatedDep = flight.estimatedDep,
            actualDep = flight.actualDep,
            scheduledArr = flight.scheduledArr,
            estimatedArr = flight.estimatedArr,
            actualArr = flight.actualArr,
            flightPct = progressPercent(flight),
            now = now,
        )
        val showBar = bar.kind != WidgetBarKind.HIDDEN
        if (showBar) {
            Spacer(GlanceModifier.height(if (compact) WidgetPad.beforeBarCompact else WidgetPad.beforeBar))
            WidgetProgress(context, bar, compact)
        }
        if (!short) {
            aircraftLine(flight)?.let { plane ->
                Spacer(GlanceModifier.height(WidgetPad.section))
                Text(
                    plane,
                    style = TextStyle(color = WidgetColors.secondaryText, fontSize = 13.sp),
                    maxLines = 1,
                )
            }
        }
        if (extra) {
            standLine(context, flight)?.let { stand ->
                Spacer(GlanceModifier.height(WidgetPad.section))
                Text(
                    stand,
                    style = TextStyle(color = WidgetColors.secondaryText, fontSize = 13.sp),
                    maxLines = 1,
                )
            }
            delayLabel(context, flight)?.let { delay ->
                Spacer(GlanceModifier.height(WidgetPad.afterDate))
                Text(
                    delay,
                    style = TextStyle(
                        color = WidgetColors.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
        val hint = hintText(context, flight, showDelay = !roomy, skipLive = showBar)
        if (hint != null) {
            Spacer(GlanceModifier.height(WidgetPad.hint))
            Text(
                hint,
                style = TextStyle(color = hintColor(flight), fontSize = 13.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CompactDepTimes(context: Context, clocks: DepClocks) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = GlanceModifier.fillMaxWidth(),
    ) {
        Text(
            "${context.getString(R.string.flight_scheduled)} ",
            style = TextStyle(color = WidgetColors.secondaryText, fontSize = 12.sp),
            maxLines = 1,
        )
        WidgetClock(clocks.planned, WidgetColors.secondaryText, 12.sp, 9.sp)
        Text(
            " · ${context.getString(R.string.flight_effective).lowercase()} ",
            style = TextStyle(color = WidgetColors.secondaryText, fontSize = 12.sp),
            maxLines = 1,
        )
        WidgetClock(
            clocks.effective,
            if (clocks.delayed) WidgetColors.error else WidgetColors.primaryText,
            12.sp,
            9.sp,
            FontWeight.Medium,
        )
    }
}

@Composable
private fun RoomyDepTimes(context: Context, clocks: DepClocks) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                context.getString(R.string.flight_dep_scheduled),
                style = TextStyle(color = WidgetColors.secondaryText, fontSize = 11.sp),
                maxLines = 1,
            )
            WidgetClock(clocks.planned, WidgetColors.primaryText, 16.sp, 10.sp, FontWeight.Bold)
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                context.getString(R.string.flight_effective),
                style = TextStyle(color = WidgetColors.secondaryText, fontSize = 11.sp),
                maxLines = 1,
            )
            WidgetClock(
                clocks.effective,
                if (clocks.delayed) WidgetColors.error else WidgetColors.primaryText,
                16.sp,
                10.sp,
                FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun WidgetProgress(context: Context, bar: WidgetBarState, compact: Boolean) {
    val fraction = (bar.percent.coerceIn(0, 100) / 100f)
    val barH = if (compact) 5.dp else 6.dp
    val label = widgetBarLabel(context, bar)
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(barH)
                .cornerRadius(4.dp)
                .background(WidgetColors.progressTrack),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (fraction > 0f) {
                val pad = if (compact) 16.dp else 20.dp
                val avail = (LocalSize.current.width - pad).coerceAtLeast(24.dp)
                Box(
                    modifier = GlanceModifier
                        .width((avail * fraction).coerceAtLeast(4.dp))
                        .height(barH)
                        .cornerRadius(4.dp)
                        .background(WidgetColors.accent),
                ) {}
            }
        }
        if (label.isNotEmpty()) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                label,
                style = TextStyle(
                    color = WidgetColors.accent,
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}

private fun widgetBarLabel(context: Context, bar: WidgetBarState): String = when (bar.kind) {
    WidgetBarKind.PREFLIGHT -> {
        val (hours, minutes) = hoursAndMinutes(bar.remainingMs ?: 0L)
        context.getString(R.string.widget_preflight_start, hours, minutes)
    }
    WidgetBarKind.INFLIGHT -> {
        val remaining = bar.remainingMs
        if (remaining != null) {
            val (hours, minutes) = hoursAndMinutes(remaining)
            context.getString(R.string.widget_inflight_remaining, hours, minutes)
        } else {
            context.getString(R.string.widget_progress, bar.percent)
        }
    }
    WidgetBarKind.LANDED -> context.getString(R.string.status_landed)
    WidgetBarKind.HIDDEN -> ""
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
private fun StatusPill(context: Context, flight: FlightEntity) {
    Text(
        statusShort(context, flight),
        modifier = GlanceModifier
            .background(WidgetColors.pill)
            .cornerRadius(20.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = TextStyle(
            color = statusColor(flight),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
    )
}

@Composable
private fun LogoBox(iata: String?, name: String?, logo: Bitmap?, boxDp: Int) {
    val initials = airlineInitials(iata, name)
    Box(
        modifier = GlanceModifier.size(boxDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(
                provider = ImageProvider(logo),
                contentDescription = name ?: iata ?: "logo",
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.size(boxDp.dp),
            )
        } else {
            Text(
                initials,
                style = TextStyle(
                    color = WidgetColors.accent,
                    fontSize = if (boxDp >= 64) 20.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

private val logoMemory = ConcurrentHashMap<String, Bitmap>()

private suspend fun loadAirlineLogo(context: Context, iata: String?): Bitmap? {
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

private fun statusColor(f: FlightEntity) = when (f.status) {
    FlightStatus.DELAYED, FlightStatus.CANCELLED, FlightStatus.DIVERTED -> WidgetColors.error
    else -> WidgetColors.accent
}

private fun hintColor(f: FlightEntity) = when {
    isEmergencySquawk(f.lastSquawk) -> WidgetColors.error
    f.status == FlightStatus.CANCELLED || f.status == FlightStatus.DIVERTED -> WidgetColors.error
    (f.delayMinutes ?: 0) > 0 -> WidgetColors.error
    else -> WidgetColors.accent
}

private fun statusShort(context: Context, f: FlightEntity): String = when (f.status) {
    FlightStatus.EN_ROUTE -> context.getString(R.string.status_en_route)
    FlightStatus.DELAYED -> context.getString(R.string.status_delayed)
    FlightStatus.BOARDING -> context.getString(R.string.status_boarding)
    FlightStatus.DEPARTED -> context.getString(R.string.status_departed)
    FlightStatus.LANDED -> context.getString(R.string.status_landed)
    FlightStatus.CANCELLED -> context.getString(R.string.status_cancelled)
    FlightStatus.DIVERTED -> context.getString(R.string.status_diverted)
    FlightStatus.SCHEDULED -> context.getString(R.string.status_on_time)
    FlightStatus.UNKNOWN -> context.getString(R.string.status_unknown)
}

private fun hintText(context: Context, f: FlightEntity, showDelay: Boolean, skipLive: Boolean): String? {
    if (isEmergencySquawk(f.lastSquawk)) return context.getString(R.string.flight_squawk, f.lastSquawk!!)
    if (f.status == FlightStatus.DIVERTED) return context.getString(R.string.status_diverted)
    if (f.status == FlightStatus.CANCELLED) return context.getString(R.string.status_cancelled)
    if (showDelay && (f.delayMinutes ?: 0) > 0) return context.getString(R.string.status_delayed_by, f.delayMinutes!!)
    if (!skipLive && isLiveStatus(f.status)) return context.getString(R.string.flight_live)
    return null
}

private fun delayLabel(context: Context, f: FlightEntity): String? {
    val minutes = f.delayMinutes ?: return null
    if (minutes <= 0) return null
    return context.getString(R.string.status_delayed_by, minutes)
}

private fun standLine(context: Context, f: FlightEntity): String? {
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

private fun progressPercent(f: FlightEntity): Int? {
    val pct = when (f.status) {
        FlightStatus.CANCELLED -> return null
        FlightStatus.LANDED -> 100
        else -> {
            val origin = f.fromLat?.let { lat -> f.fromLon?.let { lon -> LatLon(lat, lon) } }
            val dest = f.toLat?.let { lat -> f.toLon?.let { lon -> LatLon(lat, lon) } }
            val current = f.lastLat?.let { lat -> f.lastLon?.let { lon -> LatLon(lat, lon) } }
            val raw = flightProgress(
                origin = origin,
                dest = dest,
                current = current,
                scheduledDep = f.scheduledDep,
                scheduledArr = f.scheduledArr,
                estimatedDep = f.estimatedDep,
                estimatedArr = f.estimatedArr,
                actualDep = f.actualDep,
                actualArr = f.actualArr,
            )
            (raw * 100).toInt().coerceIn(0, 100)
        }
    }
    return pct
}

private fun routeLine(f: FlightEntity): String {
    val from = f.fromIata?.trim().orEmpty().ifBlank { "––" }
    val to = f.toIata?.trim().orEmpty().ifBlank { "––" }
    return "$from → $to"
}

/** `LX 64 - ZRH → MIA` — logo carries the airline; IATA as stored (ZRH, not ZHR). */
private fun headerLine(f: FlightEntity): String {
    val number = displayFlightNumber(f.flightNumber)
    return "$number - ${routeLine(f)}"
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

private fun clockOrDash(ms: Long?): String {
    if (ms == null) return "––"
    return DateTimeFmt.time(ms, DateTimeFmt.deviceZone())
}

private data class DepClocks(
    val planned: String,
    val effective: String,
    val delayed: Boolean,
)

private fun depClocks(f: FlightEntity): DepClocks {
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

private fun aircraftLine(f: FlightEntity): String? {
    val type = f.aircraftType?.trim()?.takeIf { it.isNotEmpty() }
    val reg = f.registration?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(type, reg).joinToString(" · ").takeIf { it.isNotEmpty() }
}

class FlightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FlightBuddyWidget()
}

class WidgetUpdater(private val context: Context) : KoinComponent {
    private val repo: FlightRepository by inject()

    suspend fun updateAll(changedFlightIds: Set<String> = emptySet()) {
        val manager = GlanceAppWidgetManager(context)
        val widget = FlightBuddyWidget()
        manager.getGlanceIds(FlightBuddyWidget::class.java).forEach { id ->
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
        updateAppWidgetState(context, glanceId) { it[WIDGET_FLIGHT_ID] = flightId }
        updateAll()
    }
}
