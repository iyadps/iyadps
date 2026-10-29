package org.odk.collect.android.alpha

import android.content.ContentValues
import android.content.Context
import java.io.BufferedReader

data class AlphaMapData(
    val records: List<AlphaFrameRow>,
    val boundary: List<AlphaMapPoint>,
    val totalPoints: Int
)

class AlphaOfflineMapRepository(context: Context) {

    private val helper = AlphaFieldDatabase(context)

    private fun ensureBoundaryTable() {
        val db = helper.writableDatabase
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS alpha_ea_boundary (
                ea_id TEXT NOT NULL,
                vertex_order INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_alpha_boundary_ea ON alpha_ea_boundary(ea_id, vertex_order)"
        )
    }

    fun importBoundaryCsv(reader: BufferedReader): Int {
        ensureBoundaryTable()
        val headerLine = reader.readLine()
            ?: throw IllegalArgumentException("Boundary CSV is empty.")
        val headers = AlphaCsvParser.parseLine(headerLine).map {
            it.trim().lowercase().replace(" ", "_")
        }
        fun required(name: String, vararg aliases: String): Int {
            return listOf(name, *aliases).firstNotNullOfOrNull { alias ->
                headers.indexOf(alias).takeIf { it >= 0 }
            } ?: throw IllegalArgumentException("Missing boundary column: $name")
        }
        val eaIndex = required("ea_id", "ea")
        val orderIndex = required("point_order", "vertex_order", "order")
        val latIndex = required("latitude", "lat")
        val lonIndex = required("longitude", "lon", "lng", "long")

        val db = helper.writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            db.delete("alpha_ea_boundary", null, null)
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val fields = AlphaCsvParser.parseLine(line)
                val ea = fields.getOrNull(eaIndex)?.trim().orEmpty()
                val order = fields.getOrNull(orderIndex)?.trim()?.toIntOrNull()
                val lat = fields.getOrNull(latIndex)?.trim()?.toDoubleOrNull()
                val lon = fields.getOrNull(lonIndex)?.trim()?.toDoubleOrNull()
                if (
                    ea.isBlank() || order == null || lat == null || lon == null ||
                    !AlphaMapGeometry.validCoordinate(lat, lon)
                ) {
                    throw IllegalArgumentException("Invalid EA boundary entry at data row ${count + 1}")
                }
                val values = ContentValues().apply {
                    put("ea_id", ea)
                    put("vertex_order", order)
                    put("latitude", lat)
                    put("longitude", lon)
                }
                db.insertOrThrow("alpha_ea_boundary", null, values)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun loadEa(ea: String, limit: Int = 5000): AlphaMapData {
        require(ea.isNotBlank()) { "EA assignment required before opening the map." }
        ensureBoundaryTable()
        val db = helper.readableDatabase
        val markers = mutableListOf<AlphaFrameRow>()
        db.query(
            "master_frame",
            arrayOf("record_id", "ea_id", "latitude", "longitude", "phone", "phone_norm", "status"),
            "ea_id = ? AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180",
            arrayOf(ea.trim()),
            null, null, "record_id ASC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val lat = cursor.getDouble(2)
                val lon = cursor.getDouble(3)
                if (!AlphaMapGeometry.validCoordinate(lat, lon)) continue
                markers.add(
                    AlphaFrameRow(
                        cursor.getString(0), cursor.getString(1), lat, lon,
                        if (cursor.isNull(4)) null else cursor.getString(4),
                        if (cursor.isNull(5)) null else cursor.getString(5),
                        if (cursor.isNull(6)) null else cursor.getString(6)
                    )
                )
            }
        }
        val boundary = mutableListOf<AlphaMapPoint>()
        db.query(
            "alpha_ea_boundary",
            arrayOf("latitude", "longitude"),
            "ea_id = ?",
            arrayOf(ea.trim()),
            null, null, "vertex_order ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                boundary.add(AlphaMapPoint(cursor.getDouble(0), cursor.getDouble(1)))
            }
        }
        val total = helper.countForEa(ea.trim())
        return AlphaMapData(markers, boundary, total)
    }
}
