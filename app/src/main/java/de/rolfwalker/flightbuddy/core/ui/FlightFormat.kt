package de.rolfwalker.flightbuddy.core.ui

import de.rolfwalker.flightbuddy.core.model.Units
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private const val FT_TO_M = 0.3048
private const val KT_TO_KMH = 1.852
private const val MI_TO_KM = 1.609344
private const val NM_TO_MI = 1.15078

fun appLocale(language: String): Locale =
    if (language.startsWith("en")) Locale.US else Locale.GERMAN

fun formatGrouped(value: Double, language: String, fractionDigits: Int = 0): String {
    val nf = NumberFormat.getNumberInstance(appLocale(language))
    nf.maximumFractionDigits = fractionDigits
    nf.minimumFractionDigits = 0
    nf.isGroupingUsed = true
    return nf.format(value)
}

fun formatDuration(minutes: Double?, language: String): String {
    if (minutes == null || minutes.isNaN()) return "—"
    val abs = minutes.coerceAtLeast(0.0).roundToInt()
    val days = abs / (24 * 60)
    val hours = (abs % (24 * 60)) / 60
    val mins = abs % 60
    val de = !language.startsWith("en")
    val parts = mutableListOf<String>()
    if (days > 0) {
        parts += if (de) {
            if (days == 1) "1 Tag" else "$days Tage"
        } else {
            if (days == 1) "1 day" else "$days days"
        }
    }
    if (hours > 0) parts += if (de) "$hours Std." else "${hours}h"
    if (mins > 0 || parts.isEmpty()) parts += if (de) "$mins Min." else "${mins}m"
    return parts.joinToString(" ")
}

fun formatAltitudePair(ft: Double?, language: String): Pair<String, String> {
    if (ft == null) return "—" to "—"
    val primary = "${formatGrouped(ft, language)} ft"
    val secondary = "${formatGrouped(ft * FT_TO_M, language)} ${if (language.startsWith("en")) "m AMSL" else "m ü. M."}"
    return primary to secondary
}

fun formatSpeedPair(kts: Double?, language: String): Pair<String, String> {
    if (kts == null) return "—" to "—"
    val primary = "${formatGrouped(kts, language)} kt"
    val secondary = "${formatGrouped(kts * KT_TO_KMH, language)} km/h"
    return primary to secondary
}

/** Compact map chip: FL340, or feet when still very low. */
fun formatTrafficLevel(ft: Double?): String? {
    if (ft == null || !ft.isFinite() || ft < 0) return null
    val fl = (ft / 100.0).roundToInt()
    return if (fl < 10) "${ft.roundToInt()} ft" else "FL$fl"
}

fun formatTrafficSpeedKt(kts: Double?): String? {
    if (kts == null || !kts.isFinite() || kts < 0) return null
    return "${kts.roundToInt()} kt"
}

fun formatHeading(deg: Double?, language: String): String {
    if (deg == null) return "—"
    return "${formatGrouped(deg, language)}°"
}

fun formatDistanceNm(nm: Double?, language: String, units: Units): String {
    if (nm == null) return "—"
    val miles = nm * NM_TO_MI
    return if (units == Units.IMPERIAL) {
        "${formatGrouped(miles, language)} mi"
    } else {
        "${formatGrouped(miles * MI_TO_KM, language)} km"
    }
}

fun formatDistanceMiles(miles: Double?, language: String, units: Units): String {
    if (miles == null) return "—"
    return if (units == Units.IMPERIAL) {
        "${formatGrouped(miles, language)} mi"
    } else {
        "${formatGrouped(miles * MI_TO_KM, language)} km"
    }
}

data class LegTimes(
    val planned: Long?,
    val effective: Long?,
    val effectiveKind: EffectiveKind?,
    val differ: Boolean,
) {
    val primary: Long? get() = (if (differ) effective else planned) ?: effective
}

enum class EffectiveKind { ACTUAL, ESTIMATED }

fun resolveLegTimes(scheduled: Long?, estimated: Long?, actual: Long?): LegTimes {
    val planned = scheduled
    val effective = actual ?: estimated
    val kind = when {
        actual != null -> EffectiveKind.ACTUAL
        estimated != null -> EffectiveKind.ESTIMATED
        else -> null
    }
    val differ = planned != null && effective != null && planned / 60_000L != effective / 60_000L
    return LegTimes(planned, effective, kind, differ)
}

fun isLegDelayed(times: LegTimes, delayedStatus: Boolean): Boolean {
    if (times.planned != null && times.effective != null) return times.effective > times.planned
    return times.planned != null && delayedStatus
}
