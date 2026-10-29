package org.odk.collect.android.alpha

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.BufferedReader

data class AlphaImportStats(
    val importedRows: Int,
    val duplicateRecordIds: Int,
    val duplicatePhones: Int
)

data class AlphaVisitRecord(
    val status: String,
    val note: String?,
    val revisitDate: String?,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val distanceMeters: Double?
)

class AlphaFieldDatabase(context: Context) :
    SQLiteOpenHelper(context, "alpha_field_ops.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        createMasterFrame(db)
        createVisitLog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createVisitLog(db)
        }
    }

    private fun createMasterFrame(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE master_frame (
                row_id INTEGER PRIMARY KEY AUTOINCREMENT,
                record_id TEXT NOT NULL,
                ea_id TEXT NOT NULL,
                latitude REAL,
                longitude REAL,
                phone TEXT,
                phone_norm TEXT,
                status TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_alpha_record_id ON master_frame(record_id)")
        db.execSQL("CREATE INDEX idx_alpha_ea_id ON master_frame(ea_id)")
        db.execSQL("CREATE INDEX idx_alpha_phone_norm ON master_frame(phone_norm)")
        db.execSQL("CREATE INDEX idx_alpha_ea_record ON master_frame(ea_id, record_id)")
    }

    private fun createVisitLog(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS visit_log (
                visit_id INTEGER PRIMARY KEY AUTOINCREMENT,
                record_id TEXT NOT NULL,
                ea_id TEXT NOT NULL,
                status TEXT NOT NULL,
                note TEXT,
                revisit_date TEXT,
                timestamp_ms INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                distance_meters REAL,
                enumerator_id TEXT,
                device_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_alpha_visit_record ON visit_log(record_id, timestamp_ms)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_alpha_visit_ea ON visit_log(ea_id, timestamp_ms)"
        )
    }

    fun replaceMasterFrame(reader: BufferedReader): AlphaImportStats {
        val db = writableDatabase
        val headerLine = reader.readLine()
            ?: throw IllegalArgumentException("The selected CSV file is empty.")
        val header = AlphaCsvParser.parseHeader(headerLine)

        var imported = 0
        db.beginTransaction()
        try {
            db.delete("master_frame", null, null)

            var line = reader.readLine()
            while (line != null) {
                val row = AlphaCsvParser.parseRow(line, header)
                if (row != null) {
                    val values = ContentValues().apply {
                        put("record_id", row.recordId)
                        put("ea_id", row.eaId)
                        row.latitude?.let { put("latitude", it) }
                        row.longitude?.let { put("longitude", it) }
                        row.phone?.let { put("phone", it) }
                        row.phoneNormalized?.let { put("phone_norm", it) }
                        row.status?.let { put("status", it) }
                    }
                    db.insertOrThrow("master_frame", null, values)
                    imported++
                }
                line = reader.readLine()
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return AlphaImportStats(
            importedRows = imported,
            duplicateRecordIds = duplicateCount("record_id"),
            duplicatePhones = duplicateCount("phone_norm", ignoreBlank = true)
        )
    }

    fun totalCount(): Int = scalarCount("SELECT COUNT(*) FROM master_frame")

    fun countForEa(eaId: String): Int {
        if (eaId.isBlank()) return totalCount()
        return scalarCount(
            "SELECT COUNT(*) FROM master_frame WHERE ea_id = ?",
            arrayOf(eaId.trim())
        )
    }

    fun search(query: String, eaId: String): List<AlphaFrameRow> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val phoneQuery = AlphaCsvParser.normalizePhone(trimmed)
        val args = mutableListOf<String>()
        val where = StringBuilder("(")
        where.append("record_id LIKE ?")
        args.add("%${trimmed}%")

        if (phoneQuery != null) {
            where.append(" OR phone_norm LIKE ?")
            args.add("%${phoneQuery}%")
        }
        where.append(")")

        if (eaId.isNotBlank()) {
            where.append(" AND ea_id = ?")
            args.add(eaId.trim())
        }

        val rows = mutableListOf<AlphaFrameRow>()
        readableDatabase.query(
            "master_frame",
            arrayOf("record_id", "ea_id", "latitude", "longitude", "phone", "phone_norm", "status"),
            where.toString(),
            args.toTypedArray(),
            null,
            null,
            "record_id ASC",
            "50"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    AlphaFrameRow(
                        recordId = cursor.getString(0),
                        eaId = cursor.getString(1),
                        latitude = if (cursor.isNull(2)) null else cursor.getDouble(2),
                        longitude = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        phone = if (cursor.isNull(4)) null else cursor.getString(4),
                        phoneNormalized = if (cursor.isNull(5)) null else cursor.getString(5),
                        status = if (cursor.isNull(6)) null else cursor.getString(6)
                    )
                )
            }
        }
        return rows
    }

    fun recordIdCount(recordId: String): Int {
        return scalarCount(
            "SELECT COUNT(*) FROM master_frame WHERE record_id = ?",
            arrayOf(recordId.trim())
        )
    }

    fun phoneCount(phone: String?): Int {
        val normalized = AlphaCsvParser.normalizePhone(phone) ?: return 0
        return scalarCount(
            "SELECT COUNT(*) FROM master_frame WHERE phone_norm = ?",
            arrayOf(normalized)
        )
    }

    fun saveVisit(
        row: AlphaFrameRow,
        status: String,
        note: String?,
        revisitDate: String?,
        latitude: Double?,
        longitude: Double?,
        distanceMeters: Double?,
        enumeratorId: String,
        deviceId: String
    ): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("record_id", row.recordId)
            put("ea_id", row.eaId)
            put("status", status)
            note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
            revisitDate?.takeIf { it.isNotBlank() }?.let { put("revisit_date", it) }
            put("timestamp_ms", now)
            latitude?.let { put("latitude", it) }
            longitude?.let { put("longitude", it) }
            distanceMeters?.let { put("distance_meters", it) }
            put("enumerator_id", enumeratorId)
            put("device_id", deviceId)
        }

        val id = db.insertOrThrow("visit_log", null, values)

        val frameStatus = ContentValues().apply {
            put("status", status)
        }
        db.update(
            "master_frame",
            frameStatus,
            "record_id = ? AND ea_id = ?",
            arrayOf(row.recordId, row.eaId)
        )

        return id
    }

    fun latestVisit(recordId: String, eaId: String): AlphaVisitRecord? {
        readableDatabase.query(
            "visit_log",
            arrayOf(
                "status",
                "note",
                "revisit_date",
                "timestamp_ms",
                "latitude",
                "longitude",
                "distance_meters"
            ),
            "record_id = ? AND ea_id = ?",
            arrayOf(recordId, eaId),
            null,
            null,
            "timestamp_ms DESC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null

            return AlphaVisitRecord(
                status = cursor.getString(0),
                note = if (cursor.isNull(1)) null else cursor.getString(1),
                revisitDate = if (cursor.isNull(2)) null else cursor.getString(2),
                timestamp = cursor.getLong(3),
                latitude = if (cursor.isNull(4)) null else cursor.getDouble(4),
                longitude = if (cursor.isNull(5)) null else cursor.getDouble(5),
                distanceMeters = if (cursor.isNull(6)) null else cursor.getDouble(6)
            )
        }
    }

    private fun duplicateCount(column: String, ignoreBlank: Boolean = false): Int {
        val where = if (ignoreBlank) {
            "WHERE ${column} IS NOT NULL AND ${column} <> ''"
        } else {
            ""
        }

        return scalarCount(
            """
            SELECT COALESCE(SUM(c - 1), 0)
            FROM (
                SELECT COUNT(*) AS c
                FROM master_frame
                ${where}
                GROUP BY ${column}
                HAVING COUNT(*) > 1
            )
            """.trimIndent()
        )
    }

    private fun scalarCount(sql: String, args: Array<String> = emptyArray()): Int {
        readableDatabase.rawQuery(sql, args).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
}
