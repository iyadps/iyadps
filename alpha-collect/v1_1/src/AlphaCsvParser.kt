package org.odk.collect.android.alpha

import java.util.Locale

data class AlphaFrameRow(
    val recordId: String,
    val eaId: String,
    val latitude: Double?,
    val longitude: Double?,
    val phone: String?,
    val phoneNormalized: String?,
    val status: String?
)

object AlphaCsvParser {

    data class Header(
        val recordId: Int,
        val eaId: Int,
        val latitude: Int?,
        val longitude: Int?,
        val phone: Int?,
        val status: Int?
    )

    fun parseHeader(line: String): Header {
        val columns = parseLine(line).map { normalizeHeader(it) }

        fun optional(vararg aliases: String): Int? {
            aliases.forEach { alias ->
                val index = columns.indexOf(normalizeHeader(alias))
                if (index >= 0) return index
            }
            return null
        }

        fun required(vararg aliases: String): Int {
            return optional(*aliases)
                ?: throw IllegalArgumentException("Missing required CSV column: ${aliases.first()}")
        }

        return Header(
            recordId = required("record_id", "recordid", "building_id", "household_id", "establishment_id", "id"),
            eaId = required("ea_id", "eaid", "enumeration_area", "enumeration_area_id", "ea"),
            latitude = optional("latitude", "lat", "y"),
            longitude = optional("longitude", "lon", "lng", "long", "x"),
            phone = optional("phone", "phone_number", "mobile", "telephone"),
            status = optional("status", "visit_status", "case_status")
        )
    }

    fun parseRow(line: String, header: Header): AlphaFrameRow? {
        if (line.isBlank()) return null

        val values = parseLine(line)

        fun value(index: Int?): String? {
            if (index == null || index >= values.size) return null
            return values[index].trim().takeIf { it.isNotEmpty() }
        }

        val recordId = value(header.recordId) ?: return null
        val eaId = value(header.eaId) ?: return null
        val phone = value(header.phone)

        return AlphaFrameRow(
            recordId = recordId,
            eaId = eaId,
            latitude = value(header.latitude)?.toDoubleOrNull(),
            longitude = value(header.longitude)?.toDoubleOrNull(),
            phone = phone,
            phoneNormalized = normalizePhone(phone),
            status = value(header.status)
        )
    }

    fun normalizePhone(value: String?): String? {
        val digits = value?.filter { it.isDigit() }.orEmpty()
        return digits.takeIf { it.isNotEmpty() }
    }

    fun parseLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val char = line[i]
            when {
                char == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
            i++
        }

        result.add(current.toString())
        return result
    }

    private fun normalizeHeader(value: String): String {
        return value.trim()
            .lowercase(Locale.US)
            .replace(" ", "_")
            .replace("-", "_")
    }
}
