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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.toPollInput
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.interpolateAirbornePosition
import de.rolfwalker.flightbuddy.core.domain.interpolateGreatCircle
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.model.MapStyleId
import de.rolfwalker.flightbuddy.core.ui.AirlineLogo
import de.rolfwalker.flightbuddy.core.ui.StatusBadge
import de.rolfwalker.flightbuddy.core.ui.TonalCard
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

private class FlightMapState {
    var styleId: MapStyleId? = null
    var idleBound: Boolean = false
    var framedId: String? = null
    var onViewport: ((Double, Double, Double, Double) -> Unit)? = null
}

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
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context.applicationContext) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(flights.isNotEmpty()) {
        if (flights.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(15_000)
            tick++
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                tag = FlightMapState()
                onCreate(null)
                onStart()
                onResume()
                getMapAsync { map ->
                    map.uiSettings.isAttributionEnabled = true
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(50.0, 10.0), 3.5))
                    applyBasemap(map, this, style, flights, traffic, tracks)
                }
            }
        },
        update = { view ->
            tick
            val state = view.mapState()
            state.onViewport = onViewport
            view.getMapAsync { map ->
                applyBasemap(map, view, style, flights, traffic, tracks)
                updateCamera(map, state, flights, tracks, followId, frameFlightId)
                if (!state.idleBound) {
                    state.idleBound = true
                    val emit = {
                        val b = map.projection.visibleRegion.latLngBounds
                        state.onViewport?.invoke(b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast)
                    }
                    map.addOnCameraIdleListener { emit() }
                    emit()
                }
            }
        },
        onRelease = { view ->
            view.onPause()
            view.onStop()
            view.onDestroy()
        },
    )
}

private fun MapView.mapState(): FlightMapState =
    (tag as? FlightMapState) ?: FlightMapState().also { tag = it }

private fun applyBasemap(
    map: MapLibreMap,
    view: MapView,
    styleId: MapStyleId,
    flights: List<FlightEntity>,
    traffic: List<TrafficState>,
    tracks: Map<String, List<LatLon>>,
) {
    val state = view.mapState()
    if (state.styleId != styleId) {
        state.styleId = styleId
        state.framedId = null
        map.setStyle(mapLibreStyleBuilder(styleId)) { loaded ->
            if (state.styleId == styleId) drawOverlay(loaded, flights, traffic, tracks, styleId)
        }
    } else {
        map.style?.let { drawOverlay(it, flights, traffic, tracks, styleId) }
    }
}

private fun drawOverlay(
    style: org.maplibre.android.maps.Style,
    flights: List<FlightEntity>,
    traffic: List<TrafficState>,
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
    val trafficFeatures = traffic.map { ac ->
        Feature.fromGeometry(Point.fromLngLat(ac.lon, ac.lat))
    }
    val remainingOpacity = if (trackFeatures.isEmpty()) 0.9f else 0.4f
    upsertLine(style, "arcs", arcFeatures, color, 2.2f, remainingOpacity)
    upsertLine(style, "tracks", trackFeatures, color, 3.2f, 1f)
    upsertCircles(style, "traffic", trafficFeatures, color, 4.5f)
    if (style.getImage("plane") == null) {
        style.addImage("plane", northPlaneBitmap(color))
    }
    upsertPlanes(style, planeFeatures)
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

private fun upsertCircles(
    style: org.maplibre.android.maps.Style,
    id: String,
    features: List<Feature>,
    color: String,
    radius: Float,
) {
    val collection = FeatureCollection.fromFeatures(features)
    val src = style.getSourceAs<GeoJsonSource>(id)
    if (src == null) {
        style.addSource(GeoJsonSource(id, collection))
        style.addLayer(
            CircleLayer(id, id).withProperties(
                PropertyFactory.circleColor(color),
                PropertyFactory.circleRadius(radius),
                PropertyFactory.circleStrokeWidth(1.2f),
                PropertyFactory.circleStrokeColor("#FFFFFFFF"),
                PropertyFactory.circleOpacity(0.9f),
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
                            StatusBadge(f.status, f.delayMinutes)
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
            )
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.trafficOn, onClick = { vm.setTraffic(!state.trafficOn) }, label = { Text(stringResource(R.string.map_viewport_traffic)) })
                IconButton(onClick = { vm.toggleFollow() }) { Icon(Icons.Outlined.NearMe, contentDescription = stringResource(R.string.map_follow)) }
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
                StatusBadge(selected.status, selected.delayMinutes)
                Text("${selected.fromCity} → ${selected.toCity}")
                Text(DateTimeFmt.dateTime(selected.scheduledDep, DateTimeFmt.deviceZone()))
            }
        }
    }
}
