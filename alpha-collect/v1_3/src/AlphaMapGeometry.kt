package org.odk.collect.android.alpha

import kotlin.math.cos

data class AlphaMapPoint(val latitude: Double, val longitude: Double)

object AlphaMapGeometry {

    fun metersPerDegreeLongitude(referenceLatitude: Double): Double =
        111320.0 * cos(Math.toRadians(referenceLatitude))

    fun contains(point: AlphaMapPoint, vertices: List<AlphaMapPoint>): Boolean {
        if (vertices.size < 3) return false
        var inside = false
        var j = vertices.lastIndex
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[j]
            val crosses = ((a.latitude > point.latitude) != (b.latitude > point.latitude)) &&
                point.longitude < (b.longitude - a.longitude) *
                    (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    fun validCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}
