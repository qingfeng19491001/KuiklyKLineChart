package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KLineOverlayInteractionEngineTest {
    private val bars = listOf(
        KLineBar(1_000L, 50.0, 80.0, 20.0, 50.0),
        KLineBar(2_000L, 55.0, 90.0, 30.0, 60.0),
        KLineBar(3_000L, 60.0, 95.0, 40.0, 70.0),
    )
    private val plot = KLineRect(0.0, 0.0, 100.0, 100.0)
    private val x = KLineXCoordinateSystem(plot, KLineViewport(0.0, 10.0, 10.0, 0.0), bars)
    private val y = KLineYCoordinateSystem(plot, KLineYAxis("price", 0.0, 100.0))

    @Test
    fun invalidPointByPointGeometryRejectsOnlyTheCurrentPointAndDraftCanContinue() {
        listOf("trend_line", "ray").forEach { template ->
            val (store, engine) = fixture()
            engine.beginOverlay(template, "price", draftId = "draft-$template")
            assertTrue(engine.addOverlayPoint(0.0, 50.0, x, y))

            assertFalse(engine.addOverlayPoint(0.0, 50.0, x, y))
            val draft = store.snapshot.interactionSession as KLineInteractionSession.DrawingOverlay
            assertEquals(listOf(KLineOverlayPoint(1_000L, 50.0)), draft.points)
            assertEquals(emptyList(), store.snapshot.overlayInstances)

            assertTrue(engine.addOverlayPoint(10.0, 40.0, x, y))
            assertEquals("draft-$template", store.snapshot.overlayInstances.single().id)
        }
    }

    @Test
    fun pointByPointTimestampOverflowRejectsOnlyTheOverflowingPoint() {
        val extremeBars = listOf(
            KLineBar(0L, 50.0, 60.0, 40.0, 50.0),
            KLineBar(10L, 50.0, 60.0, 40.0, 50.0),
            KLineBar(20L, 50.0, 60.0, 40.0, 50.0),
            KLineBar(Long.MAX_VALUE, 50.0, 60.0, 40.0, 50.0),
        )
        val store = KLineStore()
        store.replaceAll(extremeBars)
        val engine = KLineOverlayInteractionEngine(store, KLineBuiltInOverlays.registry())
        val extremeX = KLineXCoordinateSystem(plot, KLineViewport(0.0, 10.0, 10.0, 0.0), extremeBars)
        engine.beginOverlay("parallel_lines", "price", draftId = "overflow-draft")
        assertTrue(engine.addOverlayPoint(0.0, 50.0, extremeX, y))
        assertTrue(engine.addOverlayPoint(10.0, 40.0, extremeX, y))

        assertFalse(engine.addOverlayPoint(30.0, 30.0, extremeX, y))
        assertEquals(2, (store.snapshot.interactionSession as KLineInteractionSession.DrawingOverlay).points.size)
        assertEquals(emptyList(), store.snapshot.overlayInstances)

        assertTrue(engine.addOverlayPoint(20.0, 30.0, extremeX, y))
        assertEquals("overflow-draft", store.snapshot.overlayInstances.single().id)
    }

    @Test
    fun pointByPointAndContinuousDraftsOnlyBecomeInstancesWhenComplete() {
        val (store, engine) = fixture()
        assertEquals("draft-segment", engine.beginOverlay("segment", "price", KLineOverlayMagnetMode.STRONG, "draft-segment"))
        assertTrue(engine.addOverlayPoint(0.0, 20.0, x, y))
        assertEquals(emptyList(), store.snapshot.overlayInstances)
        assertTrue(engine.addOverlayPoint(10.0, 70.0, x, y))
        assertEquals(
            listOf(KLineOverlayPoint(1_000L, 80.0), KLineOverlayPoint(2_000L, 30.0)),
            store.snapshot.overlayInstances.single().points,
        )
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)

        engine.beginOverlay("freehand", "price", draftId = "too-short")
        engine.addOverlayPoint(0.0, 50.0, x, y)
        assertFalse(engine.finishOverlay())
        assertNull(store.snapshot.overlayInstances.find { it.id == "too-short" })

        engine.beginOverlay("freehand", "price", draftId = "freehand")
        engine.addOverlayPoint(0.0, 50.0, x, y)
        engine.addOverlayPoint(10.0, 40.0, x, y)
        engine.addOverlayPoint(20.0, 30.0, x, y)
        assertTrue(engine.finishOverlay())
        assertEquals(3, store.snapshot.overlayInstances.single { it.id == "freehand" }.points.size)
    }

    @Test
    fun dragPointAndWholeOverlayMutateStoreWhileLockedAndOverflowAreRejected() {
        val (store, engine) = fixture()
        val editable = instance("editable", listOf(point(1_000, 50.0), point(2_000, 60.0)))
        val locked = instance("locked", listOf(point(1_000, 50.0), point(2_000, 60.0)), locked = true)
        store.addOverlay(editable)
        store.addOverlay(locked)

        assertFalse(engine.beginDragPoint("locked", 0))
        assertTrue(engine.beginDragPoint("editable", 0))
        assertTrue(engine.updateDragPoint(0.0, 25.0, x, y))
        engine.endInteraction()
        assertEquals(KLineOverlayPoint(1_000L, 80.0), store.snapshot.overlayInstances.first { it.id == "editable" }.points[0])

        assertTrue(engine.beginDragOverlayAtPixel("editable", 0.0, 50.0, x, y))
        assertTrue(engine.updateDragOverlay(10.0, 40.0, x, y))
        engine.endInteraction()
        assertEquals(listOf(point(2_000, 90.0), point(3_000, 70.0)), store.snapshot.overlayInstances.first { it.id == "editable" }.points)

        val overflow = instance("overflow", listOf(point(Long.MAX_VALUE, 10.0), point(Long.MAX_VALUE - 1, 20.0)))
        store.addOverlay(overflow)
        assertTrue(engine.beginDragOverlayAtPixel("overflow", 0.0, 90.0, x, y))
        assertFalse(engine.updateDragOverlay(10.0, 80.0, x, y))
        assertEquals(overflow.points, store.snapshot.overlayInstances.first { it.id == "overflow" }.points)

        store.selectOverlay("overflow")
        assertTrue(engine.deleteSelectedOverlay())
        assertNull(store.snapshot.selectedOverlayId)
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
    }

    @Test
    fun directCancelRestoresPointAndWholeOverlayEdits() {
        val (store, engine) = fixture()
        val original = instance("editable", listOf(point(1_000, 50.0), point(2_000, 60.0)))
        store.addOverlay(original)

        assertTrue(engine.beginDragPoint("editable", 0))
        assertTrue(engine.updateDragPoint(0.0, 25.0, x, y))
        engine.cancelInteraction()
        assertEquals(original.points, store.snapshot.overlayInstances.single().points)

        assertTrue(engine.beginDragOverlayAtPixel("editable", 0.0, 50.0, x, y))
        assertTrue(engine.updateDragOverlay(10.0, 40.0, x, y))
        engine.cancelInteraction()
        assertEquals(original.points, store.snapshot.overlayInstances.single().points)
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
    }

    @Test
    fun wholeOverlayDragResolvesNoneWeakAndStrongFromPixels() {
        val expectedValues = mapOf(
            KLineOverlayMagnetMode.NONE to 25.0,
            KLineOverlayMagnetMode.WEAK to 20.0,
            KLineOverlayMagnetMode.STRONG to 0.0,
        )
        expectedValues.forEach { (mode, expectedValue) ->
            val (store, engine) = fixture()
            store.addOverlay(instance("editable", listOf(point(1_000, 50.0), point(2_000, 60.0)), magnetMode = mode))

            assertTrue(engine.beginDragOverlayAtPixel("editable", 0.0, 25.0, x, y))
            assertTrue(engine.updateDragOverlay(10.0, 50.0, x, y))
            assertEquals(KLineOverlayPoint(2_000L, expectedValue), store.snapshot.overlayInstances.single().points[0])
        }
    }

    private fun fixture(): Pair<KLineStore, KLineOverlayInteractionEngine> {
        val store = KLineStore()
        store.replaceAll(bars)
        return store to KLineOverlayInteractionEngine(store, KLineBuiltInOverlays.registry())
    }

    private fun instance(
        id: String,
        points: List<KLineOverlayPoint>,
        locked: Boolean = false,
        magnetMode: KLineOverlayMagnetMode = KLineOverlayMagnetMode.WEAK,
    ) =
        KLineOverlayInstance(
            id,
            "segment",
            paneId = "price",
            points = points,
            locked = locked,
            magnetMode = magnetMode,
        )

    private fun point(timestamp: Long, value: Double) = KLineOverlayPoint(timestamp, value)
}
