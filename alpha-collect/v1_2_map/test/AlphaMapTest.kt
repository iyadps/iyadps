package org.odk.collect.android.alpha

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class)
class AlphaMapTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val polygon = """{"type":"Feature","properties":{"ea_id":"EA1"},"bbox":[35.0,31.0,36.0,32.0],"geometry":{"type":"Polygon","coordinates":[[[35.0,31.0],[36.0,31.0],[36.0,32.0],[35.0,31.0]]]}}"""
    private fun row(id: String = "HH1", lat: Double? = 31.5, lon: Double? = 35.5) =
        AlphaFrameRow(id, "EA1", lat, lon, null, null, "pending")
    @After fun clean() { context.deleteDatabase("alpha_field_ops.db") }

    @Test fun parsesLongitudeFirstAndBounds() {
        val data = AlphaMapData.parse(polygon, "EA1")
        assertEquals(AlphaMapPoint(31.0, 35.0), data.polygons.single().single().first())
        assertEquals(AlphaMapBounds(35.0, 31.0, 36.0, 32.0), data.bounds.single())
    }
    @Test fun filtersOtherEasAndSupportsHolesAndMultiPolygons() {
        val multi = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"ea_id":"OTHER"},"geometry":{"type":"Point","coordinates":[0,0]}},{"type":"Feature","properties":{"ea_id":"EA1"},"geometry":{"type":"MultiPolygon","coordinates":[[[[35,31],[36,31],[36,32],[35,31]],[[35.1,31.1],[35.2,31.1],[35.2,31.2],[35.1,31.1]]],[[[37,31],[38,31],[38,32],[37,31]]]]}}]}"""
        val data = AlphaMapData.parse(multi, "EA1")
        assertEquals(2, data.polygons.size)
        assertEquals(2, data.polygons.first().size)
    }
    @Test fun acceptsBoundsOnlyLayer() {
        val data = AlphaMapData.parse("""{"type":"Feature","properties":{"ea_id":"EA1"},"bbox":[35,31,36,32],"geometry":null}""", "EA1")
        assertTrue(data.polygons.isEmpty()); assertEquals(1, data.bounds.size)
    }
    @Test fun rejectsMalformedWrongEaUnclosedAndInvalidBounds() {
        listOf("{}", polygon.replace("EA1", "EA2"), polygon.replace("[35.0,31.0]]]", "[35.1,31.0]]]"),
            polygon.replace("36.0,32.0]", "34.0,32.0]"), polygon.replace("31.0", "91.0"),
            "x".repeat(1_000_001)).forEach { text ->
            assertTrue("Must reject: ${text.take(100)}", runCatching { AlphaMapData.parse(text, "EA1") }.isFailure)
        }
    }
    @Test fun rejectsMissingNonFiniteOrOutOfRangeCoordinatesButAcceptsZero() {
        assertNull(AlphaMapData.point(row(lat = null)))
        assertNull(AlphaMapData.point(row(lat = Double.NaN)))
        assertNull(AlphaMapData.point(row(lon = Double.POSITIVE_INFINITY)))
        assertNull(AlphaMapData.point(row(lat = 91.0)))
        assertEquals(AlphaMapPoint(0.0, 0.0), AlphaMapData.point(row(lat = 0.0, lon = 0.0)))
    }
    @Test fun paletteHasDistinctStatusesAndUnknownFallback() {
        assertEquals(8, AlphaMapData.colors.values.toSet().size)
        assertEquals("completed", AlphaMapData.status(" COMPLETED "))
        assertEquals("unknown", AlphaMapData.status("unexpected"))
        assertEquals("pending", AlphaMapData.status(null))
    }
    @Test fun projectionAndFitAreFiniteForEmptySingleAndPolarMaps() {
        assertEquals(0.5, AlphaMapProjection.project(AlphaMapPoint(0.0, 0.0)).first, 1e-9)
        assertTrue(AlphaMapProjection.project(AlphaMapPoint(90.0, 180.0)).second.isFinite())
        val view = AlphaMapView(context); view.layout(0, 0, 800, 600); view.fit()
        view.rows = listOf(row()); view.fit()
        val screen = view.screen(AlphaMapPoint(31.5, 35.5))
        assertEquals(400f, screen.first, 0.1f); assertEquals(300f, screen.second, 0.1f)
        view.zoom(2.0); assertTrue(view.screen(AlphaMapPoint(31.5, 35.5)).first.isFinite())
    }
    @Test fun overlappingPointsAreAllReturnedWithoutAutomaticAmbiguousSelection() {
        val view = AlphaMapView(context); view.layout(0, 0, 800, 600)
        view.rows = listOf(row("HH1"), row("HH2")); view.fit()
        assertEquals(listOf("HH1", "HH2"), view.hit(400f, 300f).map { it.recordId })
        assertTrue(view.hit(0f, 0f).isEmpty())
    }
    @Test fun fullEaQueryExceedsLookupLimitScopesEaAndReflectsVisits() {
        AlphaFieldDatabase(context).use { db ->
            val csv = "record_id,ea_id,latitude,longitude,status\n" +
                (1..150).joinToString("\n") { "HH$it,EA1,31.5,35.5,pending" } + "\nOTHER,EA2,31,35,pending"
            db.replaceMasterFrame(BufferedReader(StringReader(csv)))
            assertEquals(150, db.mapRows("EA1").size)
            assertTrue(db.mapRows("").isEmpty())
            assertEquals("OTHER", db.mapRows("EA2").single().recordId)
            val first = db.mapRows("EA1").first()
            db.saveVisit(first, "completed", null, null, null, null, null, "EN1", "DEV1")
            assertEquals("completed", db.mapRows("EA1").first().status)
            assertEquals("completed", db.latestVisit(first.recordId, "EA1")?.status)
        }
    }
    @Test fun duplicateIdsRemainVisibleForResolution() {
        AlphaFieldDatabase(context).use { db ->
            db.replaceMasterFrame(BufferedReader(StringReader("record_id,ea_id,latitude,longitude\nDUP,EA1,31,35\nDUP,EA1,32,36")))
            assertEquals(2, db.mapRows("EA1").size)
            assertNull(db.mapRows("EA1").singleOrNull { it.recordId == "DUP" })
        }
    }
}
