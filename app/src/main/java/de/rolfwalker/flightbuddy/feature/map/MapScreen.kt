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
import androidx.compose.runtime.remember
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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private class FlightMapState {
    var styleId: MapStyleId? = null
    var idleBound: Boolean = false
    var onViewport: ((Double, Double, Double, Double) -> Unit)? = null
}

@Composable
fun FlightMapView(
    flights: List<FlightEntity>,
    followId: String?,
    traffic: List<de.rolfwalker.flightbuddy.core.network.TrafficState>,
    style: MapStyleId,
    modifier: Modifier = Modifier,
    onViewport: ((Double, Double, Double, Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context.applicationContext) }
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
                    applyBasemap(map, this, style, flights)
                }
            }
        },
        update = { view ->
            val state = view.mapState()
            state.onViewport = onViewport
            view.getMapAsync { map ->
                applyBasemap(map, view, style, flights)
                val follow = flights.find { it.id == followId }
                val pos = follow?.let {
                    interpolateAirbornePosition(
                        it.toPollInput(),
                        if (it.lastLat != null && it.lastLon != null) LatLon(it.lastLat, it.lastLon) else null,
                        System.currentTimeMillis(),
                    ).position
                }
                if (pos != null) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pos.lat, pos.lon), 6.0))
                }
                if (!state.idleBound) {
                    state.idleBound = true
                    map.addOnCameraIdleListener {
                        val b = map.projection.visibleRegion.latLngBounds
                        state.onViewport?.invoke(b.latitudeSouth, b.latitudeNorth, b.longitudeWest, b.longitudeEast)
                    }
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
) {
    val state = view.mapState()
    if (state.styleId != styleId) {
        state.styleId = styleId
        map.setStyle(mapLibreStyleBuilder(styleId)) { loaded ->
            if (state.styleId == styleId) drawFlights(loaded, flights, styleId)
        }
    } else {
        map.style?.let { drawFlights(it, flights, styleId) }
    }
}

private fun drawFlights(style: org.maplibre.android.maps.Style, flights: List<FlightEntity>, mapStyle: MapStyleId) {
    val features = flights.mapNotNull { f ->
        val o = if (f.fromLat != null && f.fromLon != null) LatLon(f.fromLat, f.fromLon) else return@mapNotNull null
        val d = if (f.toLat != null && f.toLon != null) LatLon(f.toLat, f.toLon) else return@mapNotNull null
        val pts = interpolateGreatCircle(o, d).map { Point.fromLngLat(it.lon, it.lat) }
        Feature.fromGeometry(LineString.fromLngLats(pts))
    }
    val src = style.getSourceAs<GeoJsonSource>("arcs")
    if (src == null) {
        style.addSource(GeoJsonSource("arcs", FeatureCollection.fromFeatures(features)))
        style.addLayer(
            LineLayer("arcs", "arcs").withProperties(
                PropertyFactory.lineColor(arcColor(mapStyle)),
                PropertyFactory.lineWidth(2.5f),
            ),
        )
    } else src.setGeoJson(FeatureCollection.fromFeatures(features))
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
                traffic = state.traffic,
                style = state.prefs.mapStyle,
                modifier = Modifier.fillMaxSize(),
                onViewport = { a, b, c, d -> vm.loadTraffic(a, b, c, d) },
            )
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.trafficOn, onClick = { vm.setTraffic(!state.trafficOn) }, label = { Text(stringResource(R.string.map_viewport_traffic)) })
                IconButton(onClick = { vm.toggleFollow() }) { Icon(Icons.Outlined.NearMe, contentDescription = stringResource(R.string.map_follow)) }
            }
            state.trafficError?.let { Text(it, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp), color = MaterialTheme.colorScheme.error) }
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
