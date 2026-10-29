package org.odk.collect.android.alpha

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class AlphaDistanceResult {
    VERIFIED,
    OUTSIDE_THRESHOLD
}

object AlphaFieldRules {

    fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val earthRadiusMeters = 6371000.0
        val lat1 = Math.toRadians(latitude1)
        val lat2 = Math.toRadians(latitude2)
        val deltaLat = Math.toRadians(latitude2 - latitude1)
        val deltaLon = Math.toRadians(longitude2 - longitude1)

        val a = sin(deltaLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return earthRadiusMeters * c
    }

    fun classifyDistance(distanceMeters: Double, thresholdMeters: Double): AlphaDistanceResult {
        return if (distanceMeters <= thresholdMeters) {
            AlphaDistanceResult.VERIFIED
        } else {
            AlphaDistanceResult.OUTSIDE_THRESHOLD
        }
    }

    fun selectRecord(rows: List<AlphaFrameRow>, query: String): AlphaFrameRow? {
        if (rows.isEmpty()) return null

        val trimmed = query.trim()
        val normalizedPhone = AlphaCsvParser.normalizePhone(trimmed)

        rows.firstOrNull { it.recordId.equals(trimmed, ignoreCase = true) }?.let {
            return it
        }

        if (normalizedPhone != null) {
            rows.firstOrNull { it.phoneNormalized == normalizedPhone }?.let {
                return it
            }
        }

        return rows.singleOrNull()
    }
}
