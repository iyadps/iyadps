package org.odk.collect.android.alpha

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.Test

class AlphaCsvParserTest {

    @Test
    fun parsesQuotedCsvAndCommonAliases() {
        val header = AlphaCsvParser.parseHeader(
            "building_id,enumeration_area,lat,lon,mobile,status"
        )

        val row = AlphaCsvParser.parseRow(
            "\"BLD,001\",EA-17,31.5123,35.1044,\"0599 123-456\",pending",
            header
        )!!

        assertThat(row.recordId, equalTo("BLD,001"))
        assertThat(row.eaId, equalTo("EA-17"))
        assertThat(row.latitude, equalTo(31.5123))
        assertThat(row.longitude, equalTo(35.1044))
        assertThat(row.phoneNormalized, equalTo("0599123456"))
        assertThat(row.status, equalTo("pending"))
    }

    @Test
    fun optionalColumnsCanBeMissing() {
        val header = AlphaCsvParser.parseHeader("record_id,ea_id")
        val row = AlphaCsvParser.parseRow("HH-1,EA-1", header)!!

        assertThat(row.recordId, equalTo("HH-1"))
        assertThat(row.phone, nullValue())
        assertThat(row.latitude, nullValue())
    }
}
