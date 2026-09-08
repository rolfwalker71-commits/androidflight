package de.rolfwalker.flightbuddy.feature.map

import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.model.MapStyleId
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Public raster basemaps — same URLs as the FlightBuddy PWA (`lib/map-styles.ts`).
 * Never use MapTiler / MapLibre Studio style URLs (`?key=`).
 */
data class PublicMapStyle(
    val tiles: List<String>,
    val tileSize: Int = 256,
    val attribution: String,
    val arcColor: String,
    val labelRes: Int,
)

val PUBLIC_MAP_STYLES: Map<MapStyleId, PublicMapStyle> = mapOf(
    MapStyleId.DARK to PublicMapStyle(
        tiles = listOf(
            "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png",
            "https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png",
            "https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png",
        ),
        attribution = "© OpenStreetMap © CARTO",
        arcColor = "#3DDCFF",
        labelRes = R.string.settings_map_dark,
    ),
    MapStyleId.VOYAGER to PublicMapStyle(
        tiles = listOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png",
        ),
        attribution = "© OpenStreetMap © CARTO",
        arcColor = "#0284c7",
        labelRes = R.string.settings_map_voyager,
    ),
    MapStyleId.POSITRON to PublicMapStyle(
        tiles = listOf(
            "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png",
            "https://b.basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png",
            "https://c.basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png",
        ),
        attribution = "© OpenStreetMap © CARTO",
        arcColor = "#0369a1",
        labelRes = R.string.settings_map_positron,
    ),
    MapStyleId.OSM to PublicMapStyle(
        tiles = listOf(
            "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png",
        ),
        attribution = "© OpenStreetMap contributors",
        arcColor = "#0369a1",
        labelRes = R.string.settings_map_osm,
    ),
    MapStyleId.SATELLITE to PublicMapStyle(
        tiles = listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        ),
        attribution = "Tiles © Esri",
        arcColor = "#3DDCFF",
        labelRes = R.string.settings_map_satellite,
    ),
    MapStyleId.TOPO to PublicMapStyle(
        tiles = listOf(
            "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
            "https://b.tile.opentopomap.org/{z}/{x}/{y}.png",
            "https://c.tile.opentopomap.org/{z}/{x}/{y}.png",
        ),
        attribution = "© OpenStreetMap, SRTM | © OpenTopoMap",
        arcColor = "#0f766e",
        labelRes = R.string.settings_map_topo,
    ),
)

fun MapStyleId.publicStyle(): PublicMapStyle =
    PUBLIC_MAP_STYLES[this] ?: PUBLIC_MAP_STYLES.getValue(MapStyleId.DARK)

fun MapStyleId.labelRes(): Int = publicStyle().labelRes

fun arcColor(id: MapStyleId): String = id.publicStyle().arcColor

/** MapLibre Style JSON identical to the PWA `mapLibreStyle()` helper. */
fun mapStyleJson(id: MapStyleId): String {
    val spec = id.publicStyle()
    return JSONObject()
        .put("version", 8)
        .put("glyphs", "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf")
        .put(
            "sources",
            JSONObject().put(
                "basemap",
                JSONObject()
                    .put("type", "raster")
                    .put("tiles", JSONArray(spec.tiles))
                    .put("tileSize", spec.tileSize)
                    .put("attribution", spec.attribution),
            ),
        )
        .put(
            "layers",
            JSONArray().put(
                JSONObject()
                    .put("id", "basemap")
                    .put("type", "raster")
                    .put("source", "basemap"),
            ),
        )
        .toString()
}

/**
 * Builds a style from public raster tiles only — no remote style URI, no MapTiler key.
 */
fun mapLibreStyleBuilder(id: MapStyleId): Style.Builder {
    val spec = id.publicStyle()
    val tileSet = TileSet("2.1.0", *spec.tiles.toTypedArray()).apply {
        attribution = spec.attribution
    }
    return Style.Builder()
        .withSource(RasterSource("basemap", tileSet, spec.tileSize))
        .withLayer(RasterLayer("basemap", "basemap"))
}
