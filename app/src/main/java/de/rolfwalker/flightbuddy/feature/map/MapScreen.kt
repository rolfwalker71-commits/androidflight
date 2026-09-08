package de.rolfwalker.flightbuddy.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.location.LocationManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.toPollInput
import de.rolfwalker.flightbuddy.core.domain.deadReckonAircraft
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.displayTrafficCallsign
import de.rolfwalker.flightbuddy.core.domain.haversineNm
import de.rolfwalker.flightbuddy.core.domain.interpolateAirbornePosition
import de.rolfwalker.flightbuddy.core.domain.interpolateGreatCircle
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.model.MapStyleId
import de.rolfwalker.flightbuddy.core.ui.AirlineLogo
import de.rolfwalker.flightbuddy.core.ui.StatusBadge
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import de.rolfwalker.flightbuddy.core.ui.status.displayFlightStatus
import de.rolfwalker.flightbuddy.core.ui.formatTrafficLevel
import de.rolfwalker.flightbuddy.core.ui.formatTrafficSpeedKt
import de.rolfwalker.flightbuddy.core.domain.initialBearing
import de.rolfwalker.flightbuddy.core.network.TrafficState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private data class TrafficFlagPos(
    val aircraft: TrafficState,
    val x: Float,
    val y: Float,
)

private class TrafficFlagSlot(
    var aircraft: TrafficState,
    val x: androidx.compose.runtime.MutableFloatState,
    val y: androidx.compose.runtime.MutableFloatState,
)

private class SmoothedPos(
    var lat: Double,
    var lon: Double,
    var heading: Double,
)

private class FlightMapState {
    var styleId: MapStyleId? = null
    var mapStyle: MapStyleId? = null
    var map: MapLibreMap? = null
    var idleBound: Boolean = false
    var framedId: String? = null
    var cameraKey: String? = null
    var moveBound: Boolean = false
    var motionPosted: Boolean = false
    var alive: Boolean = true
    var lastMotionAt: Long = 0L
    var onViewport: ((Double, Double, Double, Double) -> Unit)? = null
    var onFlags: ((List<TrafficFlagPos>) -> Unit)? = null
    var traffic: List<TrafficState> = emptyList()
    var skipIcaos: Set<String> = emptySet()
    var flights: List<FlightEntity> = emptyList()
    var tracks: Map<String, List<LatLon>> = emptyMap()
    val shown = HashMap<String, SmoothedPos>()
    val motionTick = object : Runnable {
        override fun run() {
            val view = host ?: return
            val state = view.mapState()
            if (!state.alive) {
                state.motionPosted = false
                return
            }
            state.map?.let { paintMotion(it, view) }
            view.postDelayed(this, TRAFFIC_FRAME_MS)
        }
    }
    var host: MapView? = null
    var locateId: Long = 0L
}

data class MapLocateRequest(val lat: Double, val lon: Double, val id: Long = System.currentTimeMillis())

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FlightMapView(
    flights: List<FlightEntity>,
    followId: String?,
    traffic: List<TrafficState>,
    style: MapStyleId,
    modifier: Modifier = Modifier,
    onViewport: ((Double, Double, Double, Double) -> Unit)? = null,
    tracks: Map<String, List<LatLon>> = emptyMap(),
    frameFlightId: String? = null,
    locate: MapLocateRequest? = null,
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context.applicationContext) }
    var tick by remember { mutableStateOf(0) }
    val flags = remember { mutableStateListOf<TrafficFlagSlot>() }
    val skipIcaos = remember(flights) { flights.mapNotNull { it.icao24?.lowercase() }.toSet() }
    val cameraKey = "$followId|$frameFlightId|$tick|${flights.joinToString { "${it.id}:${it.lastLat}:${it.lastLon}" }}"
    val density = LocalDensity.current
    LaunchedEffect(flights.isNotEmpty()) {
        if (flights.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15_000)
            tick++
        }
    }
    LaunchedEffect(traffic.isEmpty()) {
        if (traffic.isEmpty()) flags.clear()
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    tag = FlightMapState()
                    onCreate(null)
                    onStart()
                    onResume()
                    getMapAsync { map ->
                        map.uiSettings.isAttributionEnabled = true
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(50.0, 10.0), 3.5))
                        applyBasemap(map, this, style, flights, tracks)
                    }
                }
            },
            update = { view ->
                val state = view.mapState()
                state.alive = true
                state.host = view
                state.onViewport = onViewport
                state.traffic = traffic
                state.skipIcaos = skipIcaos
                state.flights = flights
                state.tracks = tracks
                state.mapStyle = style
                state.onFlags = { next -> syncFlagSlots(flags, next) }
                view.getMapAsync { map ->
                    state.map = map
                    applyBasemap(map, view, style, flights, tracks)
                    if (locate != null && state.locateId != locate.id) {
                        state.locateId = locate.id
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(locate.lat, locate.lon), 10.0))
                    }
                    if (state.cameraKey != cameraKey) {
                        state.cameraKey = cameraKey
                        updateCamera(map, state, flights, tracks, followId, frameFlightId)
                    }
                    if (!state.idleBound) {
                        state.idleBound = true
                        val emit = {
                            val b = map.projection.visibleRegion.latLngBounds
                            state.onViewport?.invoke(b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast)
                        }
                        map.addOnCameraIdleListener { emit() }
                        emit()
                    }
                    if (!state.moveBound) {
                        state.moveBound = true
                        map.addOnCameraMoveListener { state.onFlags?.invoke(projectTrafficFlags(map, view)) }
                    }
                    startMotion(view)
                }
            },
            onRelease = { view ->
                val state = view.mapState()
                state.alive = false
                state.motionPosted = false
                view.removeCallbacks(state.motionTick)
                view.onPause()
                view.onStop()
                view.onDestroy()
            },
        )
        Box(Modifier.fillMaxSize().pointerInteropFilter { false }) {
            val labelOffset = with(density) { 18.dp.roundToPx() }
            flags.forEach { flag ->
                key(flag.aircraft.icao24) {
                    TrafficCallsignFlag(
                        aircraft = flag.aircraft,
                        modifier = Modifier.offset {
                            IntOffset(flag.x.floatValue.toInt() + labelOffset, flag.y.floatValue.toInt() - 20)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficCallsignFlag(aircraft: TrafficState, modifier: Modifier = Modifier) {
    val callsign = displayTrafficCallsign(aircraft.callsign, aircraft.icao24)
    val airline = aircraft.airlineName?.trim()?.takeIf { it.isNotEmpty() }
    val level = formatTrafficLevel(aircraft.altitudeFt)
    val speed = formatTrafficSpeedKt(aircraft.velocityKts)
    val telem = listOfNotNull(level, speed).joinToString(" · ").ifBlank { null }
    val label = buildString {
        append(callsign)
        if (airline != null) append(", ").append(airline)
        if (telem != null) append(", ").append(telem)
    }
    Surface(
        modifier = modifier.semantics { contentDescription = label }.widthIn(max = 136.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AirlineLogo(aircraft.airlineIata, airline ?: callsign, 18)
            Column {
                Text(
                    callsign,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                if (telem != null) {
                    Text(
                        telem,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                } else if (airline != null) {
                    Text(
                        airline,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun MapView.mapState(): FlightMapState =
    (tag as? FlightMapState) ?: FlightMapState().also { tag = it }

private fun applyBasemap(
    map: MapLibreMap,
    view: MapView,
    styleId: MapStyleId,
    flights: List<FlightEntity>,
    tracks: Map<String, List<LatLon>>,
) {
    val state = view.mapState()
    if (state.styleId != styleId) {
        state.styleId = styleId
        state.framedId = null
        map.setStyle(mapLibreStyleBuilder(styleId)) { loaded ->
            if (state.styleId == styleId) {
                drawOverlay(loaded, flights, tracks, styleId)
                paintTraffic(map, view, styleId)
            }
        }
    } else {
        map.style?.let { drawOverlay(it, flights, tracks, styleId) }
    }
}

private fun drawOverlay(
    style: org.maplibre.android.maps.Style,
    flights: List<FlightEntity>,
    tracks: Map<String, List<LatLon>>,
    mapStyle: MapStyleId,
) {
    val color = arcColor(mapStyle)
    val now = System.currentTimeMillis()
    val arcFeatures = flights.mapNotNull { f ->
        val dest = if (f.toLat != null && f.toLon != null) LatLon(f.toLat, f.toLon) else return@mapNotNull null
        val recorded = tracks[f.id].orEmpty()
        val from = currentPlane(f, now)
            ?: recorded.lastOrNull()
            ?: if (f.fromLat != null && f.fromLon != null) LatLon(f.fromLat, f.fromLon) else return@mapNotNull null
        val pts = interpolateGreatCircle(from, dest).map { Point.fromLngLat(it.lon, it.lat) }
        Feature.fromGeometry(LineString.fromLngLats(pts))
    }
    val trackFeatures = flights.mapNotNull { f ->
        val pts = flownTrack(f, tracks[f.id].orEmpty(), now)
        if (pts.size < 2) return@mapNotNull null
        Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) }))
    }
    val planeFeatures = flights.mapNotNull { f ->
        val pos = currentPlane(f, now) ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(pos.lon, pos.lat)).also { feat ->
            feat.addNumberProperty("heading", planeHeading(f, pos, tracks[f.id].orEmpty()))
        }
    }
    val remainingOpacity = if (trackFeatures.isEmpty()) 0.9f else 0.4f
    upsertLine(style, "arcs", arcFeatures, color, 2.2f, remainingOpacity)
    upsertLine(style, "tracks", trackFeatures, color, 3.2f, 1f)
    if (style.getImage("plane") == null) {
        style.addImage("plane", northPlaneBitmap(color))
    }
    upsertPlanes(style, planeFeatures)
}

private const val TRAFFIC_SOURCE = "viewport-traffic"
private const val TRAFFIC_LAYER = "viewport-traffic-planes"
private const val TRAFFIC_DOTS = "viewport-traffic-dots"
private const val TRAFFIC_FLAG_MAX = 36
private const val TRAFFIC_FLAG_MIN_ZOOM = 4.8
private const val TRAFFIC_FRAME_MS = 50L
private const val TRAFFIC_SMOOTH_TAU_S = 0.45

private fun startMotion(view: MapView) {
    val state = view.mapState()
    if (state.motionPosted) return
    state.motionPosted = true
    state.host = view
    view.removeCallbacks(state.motionTick)
    view.post(state.motionTick)
}

private fun paintMotion(map: MapLibreMap, view: MapView) {
    val style = map.style ?: return
    val state = view.mapState()
    val mapStyle = state.mapStyle ?: return
    val color = arcColor(mapStyle)
    if (style.getImage("plane") == null) {
        style.addImage("plane", northPlaneBitmap(color))
    }
    val now = System.currentTimeMillis()
    val dtS = if (state.lastMotionAt == 0L) {
        TRAFFIC_FRAME_MS / 1000.0
    } else {
        ((now - state.lastMotionAt) / 1000.0).coerceIn(0.016, 0.2)
    }
    state.lastMotionAt = now
    val alpha = (1.0 - kotlin.math.exp(-dtS / TRAFFIC_SMOOTH_TAU_S)).coerceIn(0.06, 1.0)
    val live = HashSet<String>(state.traffic.size)
    val features = state.traffic.mapNotNull { ac ->
        if (ac.icao24 in state.skipIcaos) return@mapNotNull null
        live += ac.icao24
        val target = deadReckonAircraft(ac.lat, ac.lon, ac.heading, ac.velocityKts, now - ac.observedAt)
        val heading = ac.heading ?: 0.0
        val shown = state.shown[ac.icao24]
        val pos = if (shown == null || haversineNm(LatLon(shown.lat, shown.lon), target) > 20.0) {
            state.shown[ac.icao24] = SmoothedPos(target.lat, target.lon, heading)
            target
        } else {
            shown.lat += (target.lat - shown.lat) * alpha
            shown.lon += (target.lon - shown.lon) * alpha
            shown.heading = lerpHeading(shown.heading, heading, alpha)
            LatLon(shown.lat, shown.lon)
        }
        Feature.fromGeometry(Point.fromLngLat(pos.lon, pos.lat)).also { feat ->
            feat.addNumberProperty("heading", state.shown[ac.icao24]?.heading ?: heading)
            feat.addStringProperty("icao24", ac.icao24)
        }
    }
    state.shown.keys.retainAll(live)
    upsertTrafficPlanes(style, features, color)
    val planeFeatures = state.flights.mapNotNull { f ->
        val pos = currentPlane(f, now) ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(pos.lon, pos.lat)).also { feat ->
            feat.addNumberProperty("heading", planeHeading(f, pos, state.tracks[f.id].orEmpty()))
        }
    }
    upsertPlanes(style, planeFeatures)
    state.onFlags?.invoke(projectTrafficFlags(map, view))
}

private fun syncFlagSlots(
    slots: SnapshotStateList<TrafficFlagSlot>,
    next: List<TrafficFlagPos>,
) {
    val incoming = next.associateBy { it.aircraft.icao24 }
    slots.removeAll { it.aircraft.icao24 !in incoming }
    val existing = slots.associateBy { it.aircraft.icao24 }
    for (pos in next) {
        val slot = existing[pos.aircraft.icao24]
        if (slot != null) {
            slot.x.floatValue = pos.x
            slot.y.floatValue = pos.y
            slot.aircraft = pos.aircraft
        } else {
            slots.add(
                TrafficFlagSlot(
                    aircraft = pos.aircraft,
                    x = mutableFloatStateOf(pos.x),
                    y = mutableFloatStateOf(pos.y),
                ),
            )
        }
    }
}

private fun lerpHeading(from: Double, to: Double, t: Double): Double {
    var delta = (to - from) % 360.0
    if (delta > 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    return ((from + delta * t) % 360.0 + 360.0) % 360.0
}

private fun paintTraffic(map: MapLibreMap, view: MapView, mapStyle: MapStyleId) {
    view.mapState().mapStyle = mapStyle
    paintMotion(map, view)
    view.mapState().onFlags?.invoke(projectTrafficFlags(map, view))
}

private fun projectTrafficFlags(
    map: MapLibreMap,
    view: MapView,
): List<TrafficFlagPos> {
    val state = view.mapState()
    if (state.traffic.isEmpty() || map.cameraPosition.zoom < TRAFFIC_FLAG_MIN_ZOOM) return emptyList()
    val w = view.width.toFloat()
    val h = view.height.toFloat()
    if (w < 8f || h < 8f) return emptyList()
    val now = System.currentTimeMillis()
    val visible = state.traffic.mapNotNull { ac ->
        if (ac.icao24 in state.skipIcaos) return@mapNotNull null
        val shown = state.shown[ac.icao24]
        val pos = if (shown != null) {
            LatLon(shown.lat, shown.lon)
        } else {
            deadReckonAircraft(ac.lat, ac.lon, ac.heading, ac.velocityKts, now - ac.observedAt)
        }
        val screen = map.projection.toScreenLocation(LatLng(pos.lat, pos.lon))
        if (screen.x < -120f || screen.y < -48f || screen.x > w + 24f || screen.y > h + 24f) return@mapNotNull null
        TrafficFlagPos(ac, screen.x, screen.y)
    }
    if (visible.size <= TRAFFIC_FLAG_MAX) return visible
    val cx = w / 2f
    val cy = h / 2f
    return visible.sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }.take(TRAFFIC_FLAG_MAX)
}

private fun upsertTrafficPlanes(
    style: org.maplibre.android.maps.Style,
    features: List<Feature>,
    color: String,
) {
    val collection = FeatureCollection.fromFeatures(features)
    val src = style.getSourceAs<GeoJsonSource>(TRAFFIC_SOURCE)
    if (src == null) style.addSource(GeoJsonSource(TRAFFIC_SOURCE, collection))
    else src.setGeoJson(collection)
    if (style.getLayer(TRAFFIC_DOTS) == null) {
        addTrafficLayer(
            style,
            CircleLayer(TRAFFIC_DOTS, TRAFFIC_SOURCE).withProperties(
                PropertyFactory.circleColor(color),
                PropertyFactory.circleRadius(5.5f),
                PropertyFactory.circleStrokeWidth(1.4f),
                PropertyFactory.circleStrokeColor("#FFFFFFFF"),
                PropertyFactory.circleOpacity(0.95f),
            ),
        )
    }
    if (style.getLayer(TRAFFIC_LAYER) == null) {
        addTrafficLayer(
            style,
            SymbolLayer(TRAFFIC_LAYER, TRAFFIC_SOURCE).withProperties(
                PropertyFactory.iconImage("plane"),
                PropertyFactory.iconSize(0.78f),
                PropertyFactory.iconRotate(Expression.get("heading")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }
}

private fun addTrafficLayer(style: org.maplibre.android.maps.Style, layer: org.maplibre.android.style.layers.Layer) {
    try {
        if (style.getLayer("planes") != null) style.addLayerBelow(layer, "planes")
        else style.addLayer(layer)
    } catch (_: Exception) {
        runCatching { style.addLayer(layer) }
    }
}

private fun upsertLine(
    style: org.maplibre.android.maps.Style,
    id: String,
    features: List<Feature>,
    color: String,
    width: Float,
    opacity: Float,
) {
    val collection = FeatureCollection.fromFeatures(features)
    val src = style.getSourceAs<GeoJsonSource>(id)
    if (src == null) {
        style.addSource(GeoJsonSource(id, collection))
        style.addLayer(
            LineLayer(id, id).withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(width),
                PropertyFactory.lineOpacity(opacity),
            ),
        )
    } else {
        src.setGeoJson(collection)
    }
}

private fun upsertPlanes(style: org.maplibre.android.maps.Style, features: List<Feature>) {
    val collection = FeatureCollection.fromFeatures(features)
    val src = style.getSourceAs<GeoJsonSource>("planes")
    if (src == null) {
        style.addSource(GeoJsonSource("planes", collection))
        style.addLayer(
            SymbolLayer("planes", "planes").withProperties(
                PropertyFactory.iconImage("plane"),
                PropertyFactory.iconSize(1f),
                PropertyFactory.iconRotate(Expression.get("heading")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    } else {
        src.setGeoJson(collection)
    }
}

private fun currentPlane(f: FlightEntity, now: Long): LatLon? {
    val last = if (f.lastLat != null && f.lastLon != null) LatLon(f.lastLat, f.lastLon) else null
    return interpolateAirbornePosition(f.toPollInput(), last, now).position
}

private fun flownTrack(f: FlightEntity, recorded: List<LatLon>, now: Long): List<LatLon> {
    val live = currentPlane(f, now)
    val pts = recorded.toMutableList()
    if (pts.size < 2) {
        val origin = if (f.fromLat != null && f.fromLon != null) LatLon(f.fromLat, f.fromLon) else null
        if (origin != null && live != null && (origin.lat != live.lat || origin.lon != live.lon)) {
            return interpolateGreatCircle(origin, live)
        }
    }
    live?.let { pos ->
        val last = pts.lastOrNull()
        if (last == null || last.lat != pos.lat || last.lon != pos.lon) pts += pos
    }
    return pts
}

private fun planeHeading(f: FlightEntity, pos: LatLon, recorded: List<LatLon>): Double {
    f.lastHeading?.let { return it }
    if (recorded.size >= 2) return initialBearing(recorded[recorded.lastIndex - 1], recorded.last())
    val dest = if (f.toLat != null && f.toLon != null) LatLon(f.toLat, f.toLon) else null
    if (dest != null) return initialBearing(pos, dest)
    return 0.0
}

private fun updateCamera(
    map: MapLibreMap,
    state: FlightMapState,
    flights: List<FlightEntity>,
    tracks: Map<String, List<LatLon>>,
    followId: String?,
    frameFlightId: String?,
) {
    val now = System.currentTimeMillis()
    if (followId != null) {
        val follow = flights.find { it.id == followId } ?: return
        val pos = currentPlane(follow, now) ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.lat, pos.lon), 6.0))
        return
    }
    val frameId = frameFlightId ?: return
    if (state.framedId == frameId) return
    val flight = flights.find { it.id == frameId } ?: return
    val pts = mutableListOf<LatLng>()
    if (flight.fromLat != null && flight.fromLon != null) pts += LatLng(flight.fromLat, flight.fromLon)
    if (flight.toLat != null && flight.toLon != null) pts += LatLng(flight.toLat, flight.toLon)
    flownTrack(flight, tracks[flight.id].orEmpty(), now).forEach { pts += LatLng(it.lat, it.lon) }
    currentPlane(flight, now)?.let { pts += LatLng(it.lat, it.lon) }
    if (pts.isEmpty()) return
    state.framedId = frameId
    if (pts.size == 1) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(pts.first(), 6.0))
    } else {
        val bounds = LatLngBounds.Builder().apply { pts.forEach { include(it) } }.build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 56))
    }
}

@Composable
fun HomeHeroMap(flights: List<FlightEntity>, mapStyle: MapStyleId, onOpen: (String) -> Unit) {
    TonalCard(Modifier.fillMaxSize()) {
        Column {
            FlightMapView(flights, flights.firstOrNull()?.id, emptyList(), mapStyle, Modifier.weight(1f))
            LazyColumn(Modifier.height(160.dp).padding(8.dp)) {
                items(flights) { f ->
                    Text(displayFlightNumber(f.flightNumber), modifier = Modifier.clickable { onOpen(f.id) }.padding(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(tablet: Boolean, vm: MapViewModel, onOpen: (String) -> Unit) {
    val state by vm.state.collectAsState()
    val selected = state.flights.find { it.id == state.selectedId }
    val context = LocalContext.current
    var locate by remember { mutableStateOf<MapLocateRequest?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate = lastKnownFix(context)
    }
    val goToMe = {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) locate = lastKnownFix(context)
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    Row(Modifier.fillMaxSize()) {
        if (tablet) {
            LazyColumn(Modifier.fillMaxHeight().weight(0.38f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.map_tracked), style = MaterialTheme.typography.titleMedium) }
                items(state.flights) { f ->
                    TonalCard(Modifier.fillMaxWidth().clickable { vm.select(f.id); onOpen(f.id) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AirlineLogo(f.airlineIata, f.airlineName, 29)
                            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                Text(displayFlightNumber(f.flightNumber))
                                Text(
                                    "${f.fromIata}–${f.toIata} · ${DateTimeFmt.dateTime(f.scheduledDep, DateTimeFmt.deviceZone())}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            StatusBadge(displayFlightStatus(f), f.delayMinutes)
                        }
                    }
                }
                item { Text(stringResource(R.string.map_objects), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                if (state.objects.isEmpty()) item { Text(stringResource(R.string.map_objects_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.objects) { o ->
                    Text("${o.callsign} · ${o.starts}/${o.landings}", modifier = Modifier.padding(8.dp))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            FlightMapView(
                flights = state.flights,
                followId = if (state.follow) state.selectedId else null,
                traffic = if (state.trafficOn) state.traffic else emptyList(),
                style = state.prefs.mapStyle,
                modifier = Modifier.fillMaxSize(),
                onViewport = { a, b, c, d -> vm.loadTraffic(a, b, c, d) },
                tracks = state.tracks,
                locate = locate,
            )
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.trafficOn, onClick = { vm.setTraffic(!state.trafficOn) }, label = { Text(stringResource(R.string.map_viewport_traffic)) })
                IconButton(onClick = { vm.toggleFollow() }) { Icon(Icons.Outlined.NearMe, contentDescription = stringResource(R.string.map_follow)) }
                IconButton(onClick = goToMe) { Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.map_locate)) }
            }
            if (state.trafficOn) {
                val caption = when {
                    state.trafficZoom -> stringResource(R.string.map_viewport_traffic_zoom)
                    state.trafficError != null -> state.trafficError!!
                    else -> stringResource(R.string.map_viewport_traffic_live, state.traffic.size)
                }
                Text(
                    caption,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.trafficError != null && !state.trafficZoom) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (!tablet && selected != null) {
                TonalCard(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().clickable { onOpen(selected.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AirlineLogo(selected.airlineIata, selected.airlineName)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(displayFlightNumber(selected.flightNumber), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${selected.fromIata}–${selected.toIata} · ${DateTimeFmt.dateTime(selected.scheduledDep, DateTimeFmt.deviceZone())}",
                            )
                        }
                    }
                }
            }
        }
        if (tablet && selected != null) {
            Column(Modifier.weight(0.4f).padding(12.dp)) {
                Text(displayFlightNumber(selected.flightNumber), style = MaterialTheme.typography.headlineSmall)
                StatusBadge(displayFlightStatus(selected), selected.delayMinutes)
                Text("${selected.fromCity} → ${selected.toCity}")
                Text(DateTimeFmt.dateTime(selected.scheduledDep, DateTimeFmt.deviceZone()))
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun lastKnownFix(context: android.content.Context): MapLocateRequest? {
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .firstNotNullOfOrNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        ?: return null
    return MapLocateRequest(loc.latitude, loc.longitude)
}
