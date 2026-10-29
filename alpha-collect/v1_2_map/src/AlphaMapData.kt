package org.odk.collect.android.alpha

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

data class AlphaMapPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0) { "Invalid WGS84 coordinate" }
    }
}

data class AlphaMapBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    init {
        AlphaMapPoint(south, west)
        AlphaMapPoint(north, east)
        require(west <= east && south <= north) { "Split antimeridian bounds before import" }
    }
    fun corners() = listOf(AlphaMapPoint(south, west), AlphaMapPoint(north, east))
}

data class AlphaMapOverlay(
    val polygons: List<List<List<AlphaMapPoint>>> = emptyList(),
    val bounds: List<AlphaMapBounds> = emptyList()
) {
    fun points() = polygons.flatten().flatten() + bounds.flatMap { it.corners() }
}

object AlphaMapData {
    fun point(row: AlphaFrameRow): AlphaMapPoint? = runCatching {
        AlphaMapPoint(requireNotNull(row.latitude), requireNotNull(row.longitude))
    }.getOrNull()

    // Stable palette also used by the visible legend. Unknown values remain distinct.
    val colors = linkedMapOf(
        "pending" to 0xFF546E7A.toInt(), "completed" to 0xFF2E7D32.toInt(),
        "refused" to 0xFFC62828.toInt(), "revisit" to 0xFFF9A825.toInt(),
        "vacant" to 0xFF6A1B9A.toInt(), "not_found" to 0xFF795548.toInt(),
        "unavailable" to 0xFFEF6C00.toInt(), "unknown" to 0xFF212121.toInt()
    )
    fun status(value: String?) = value?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { colors.containsKey(it) } ?: if (value.isNullOrBlank()) "pending" else "unknown"

    // GeoJSON positions are longitude, latitude (RFC 7946), never CSV order.
    fun parse(text: String, ea: String): AlphaMapOverlay {
        require(ea.isNotBlank()) { "Save an assigned EA first" }
        require(text.length <= 1_000_000) { "Map file exceeds 1 MB" }
        val root = JSONObject(text)
        require(!root.has("crs")) { "Use WGS84 GeoJSON without a crs member" }
        val features = when (root.getString("type")) {
            "FeatureCollection" -> root.getJSONArray("features")
            "Feature" -> JSONArray().put(root)
            else -> throw IllegalArgumentException("Import a GeoJSON FeatureCollection or Feature")
        }
        val polygons = mutableListOf<List<List<AlphaMapPoint>>>()
        val bounds = mutableListOf<AlphaMapBounds>()
        var matched = false
        var vertices = 0
        fun polygon(array: JSONArray): List<List<AlphaMapPoint>> {
            require(array.length() > 0) { "Empty polygon" }
            return (0 until array.length()).map { ringIndex ->
                val ring = array.getJSONArray(ringIndex)
                require(ring.length() >= 4) { "Polygon rings need at least four positions" }
                val points = (0 until ring.length()).map { i ->
                    require(++vertices <= 20000) { "Map exceeds 20,000 vertices" }
                    val position = ring.getJSONArray(i)
                    AlphaMapPoint(position.getDouble(1), position.getDouble(0))
                }
                require(points.first() == points.last()) { "Polygon rings must be closed" }
                require(points.zipWithNext().all { abs(it.first.longitude - it.second.longitude) <= 180 }) {
                    "Split polygons crossing the antimeridian before import"
                }
                points
            }
        }
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            require(feature.getString("type") == "Feature")
            if (feature.optJSONObject("properties")?.optString("ea_id") != ea) continue
            matched = true
            require(!feature.has("crs"))
            feature.optJSONArray("bbox")?.let { b ->
                require(b.length() == 4) { "Use 2D bbox: west, south, east, north" }
                bounds.add(AlphaMapBounds(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)))
            }
            if (!feature.isNull("geometry")) {
                val geometry = feature.getJSONObject("geometry")
                when (geometry.getString("type")) {
                    "Polygon" -> polygons.add(polygon(geometry.getJSONArray("coordinates")))
                    "MultiPolygon" -> {
                        val parts = geometry.getJSONArray("coordinates")
                        for (p in 0 until parts.length()) polygons.add(polygon(parts.getJSONArray(p)))
                    }
                    else -> throw IllegalArgumentException("EA geometry must be Polygon or MultiPolygon")
                }
            }
        }
        require(matched && (polygons.isNotEmpty() || bounds.isNotEmpty())) {
            "No polygon or layer bounds for assigned EA $ea"
        }
        return AlphaMapOverlay(polygons, bounds)
    }
}

// Web Mercator in normalized world coordinates. Clamp polar positions for display only.
object AlphaMapProjection {
    fun project(point: AlphaMapPoint): Pair<Double, Double> {
        val lat = Math.toRadians(point.latitude.coerceIn(-85.05112878, 85.05112878))
        return (point.longitude + 180) / 360 to (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2
    }
}
