package org.odk.collect.android.alpha

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

class AlphaMapGeometryTest {
    @Test
    fun pointInsideEaBoundary() {
        val boundary = listOf(
            AlphaMapPoint(31.50, 35.00),
            AlphaMapPoint(31.50, 35.02),
            AlphaMapPoint(31.52, 35.02),
            AlphaMapPoint(31.52, 35.00)
        )
        assertThat(AlphaMapGeometry.contains(AlphaMapPoint(31.51, 35.01), boundary), equalTo(true))
        assertThat(AlphaMapGeometry.contains(AlphaMapPoint(31.53, 35.01), boundary), equalTo(false))
    }

    @Test
    fun degeneratePolygonDoesNotMatch() {
        val boundary = listOf(AlphaMapPoint(31.50, 35.00), AlphaMapPoint(31.51, 35.01))
        assertThat(AlphaMapGeometry.contains(AlphaMapPoint(31.50, 35.00), boundary), equalTo(false))
    }

    @Test
    fun validateWgs84Coordinates() {
        assertThat(AlphaMapGeometry.validCoordinate(31.532, 35.095), equalTo(true))
        assertThat(AlphaMapGeometry.validCoordinate(91.0, 35.095), equalTo(false))
        assertThat(AlphaMapGeometry.validCoordinate(31.532, Double.NaN), equalTo(false))
    }
}
