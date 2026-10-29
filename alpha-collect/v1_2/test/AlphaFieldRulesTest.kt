package org.odk.collect.android.alpha

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.Test

class AlphaFieldRulesTest {

    @Test
    fun distanceCalculationIsReasonable() {
        val distance = AlphaFieldRules.distanceMeters(
            31.53210,
            35.09520,
            31.53310,
            35.09520
        )

        assertThat(distance, closeTo(111.2, 1.0))
    }

    @Test
    fun distanceClassificationUsesConfiguredThreshold() {
        assertThat(
            AlphaFieldRules.classifyDistance(85.0, 100.0),
            equalTo(AlphaDistanceResult.VERIFIED)
        )
        assertThat(
            AlphaFieldRules.classifyDistance(125.0, 100.0),
            equalTo(AlphaDistanceResult.OUTSIDE_THRESHOLD)
        )
    }

    @Test
    fun exactRecordIsSelectedFromMultipleSearchResults() {
        val rows = listOf(
            AlphaFrameRow("HH-100", "EA-1", null, null, "0599000001", "0599000001", null),
            AlphaFrameRow("HH-1000", "EA-1", null, null, "0599000002", "0599000002", null)
        )

        val selected = AlphaFieldRules.selectRecord(rows, "HH-100")

        assertThat(selected?.recordId, equalTo("HH-100"))
    }

    @Test
    fun ambiguousPartialSearchDoesNotSelectCase() {
        val rows = listOf(
            AlphaFrameRow("HH-100", "EA-1", null, null, null, null, null),
            AlphaFrameRow("HH-101", "EA-1", null, null, null, null, null)
        )

        assertThat(AlphaFieldRules.selectRecord(rows, "HH-10"), nullValue())
    }
}
