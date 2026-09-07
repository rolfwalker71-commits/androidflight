package de.rolfwalker.flightbuddy.core.domain

import de.rolfwalker.flightbuddy.core.model.LatLon
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val NM_EARTH = 3440.065
private fun toRad(d: Double) = d * Math.PI / 180.0
private fun toDeg(r: Double) = r * 180.0 / Math.PI

fun haversineNm(a: LatLon, b: LatLon): Double {
    val dLat = toRad(b.lat - a.lat)
    val dLon = toRad(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(toRad(a.lat)) * cos(toRad(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * NM_EARTH * asin(min(1.0, sqrt(h)))
}

fun haversineMiles(a: LatLon, b: LatLon) = haversineNm(a, b) * 1.15078

fun interpolateGreatCircle(a: LatLon, b: LatLon, steps: Int = 64): List<LatLon> {
    val φ1 = toRad(a.lat)
    val λ1 = toRad(a.lon)
    val φ2 = toRad(b.lat)
    val λ2 = toRad(b.lon)
    val Δ = 2 * asin(sqrt(sin((φ2 - φ1) / 2).pow(2) + cos(φ1) * cos(φ2) * sin((λ2 - λ1) / 2).pow(2)))
    if (Δ == 0.0 || !Δ.isFinite()) return listOf(a, b)
    return (0..steps).map { i ->
        val f = i.toDouble() / steps
        val A = sin((1 - f) * Δ) / sin(Δ)
        val B = sin(f * Δ) / sin(Δ)
        val x = A * cos(φ1) * cos(λ1) + B * cos(φ2) * cos(λ2)
        val y = A * cos(φ1) * sin(λ1) + B * cos(φ2) * sin(λ2)
        val z = A * sin(φ1) + B * sin(φ2)
        LatLon(toDeg(atan2(z, sqrt(x * x + y * y))), toDeg(atan2(y, x)))
    }
}

fun pointAlongGreatCircle(a: LatLon, b: LatLon, fraction: Double): LatLon {
    val f = fraction.coerceIn(0.0, 1.0)
    val pts = interpolateGreatCircle(a, b, 128)
    val idx = (f * (pts.size - 1)).toInt().coerceIn(0, pts.lastIndex)
    return pts[idx]
}

fun flightProgress(
    origin: LatLon?,
    dest: LatLon?,
    current: LatLon?,
    scheduledDep: Long?,
    scheduledArr: Long?,
    estimatedDep: Long?,
    estimatedArr: Long?,
    actualDep: Long?,
    actualArr: Long?,
    now: Long = System.currentTimeMillis(),
): Double {
    if (origin != null && dest != null && current != null) {
        val total = haversineNm(origin, dest)
        val remaining = haversineNm(current, dest)
        if (total > 0) return (1 - remaining / total).coerceIn(0.02, 0.98)
    }
    val start = actualDep ?: estimatedDep ?: scheduledDep ?: return 0.0
    val end = actualArr ?: estimatedArr ?: scheduledArr ?: return 0.0
    val span = end - start
    if (span <= 0) return 0.0
    return ((now - start).toDouble() / span).coerceIn(0.0, 0.98)
}

fun destinationPoint(start: LatLon, headingDeg: Double, distanceNm: Double): LatLon {
    if (!distanceNm.isFinite() || distanceNm == 0.0) return start
    val δ = distanceNm / NM_EARTH
    val θ = toRad(headingDeg)
    val φ1 = toRad(start.lat)
    val λ1 = toRad(start.lon)
    val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
    val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
    var lon = toDeg(λ2)
    lon = ((lon + 540) % 360) - 180
    return LatLon(toDeg(φ2), lon)
}
