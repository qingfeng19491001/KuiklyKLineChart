package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KLineViewportInteractionEngineTest {
    private val plot = KLineRect(0.0, 0.0, 200.0, 100.0)
    private val bars = (0L until 100L).map { KLineBar(it * 1_000L, 50.0, 60.0, 40.0, 50.0) }
    private val y = KLineYCoordinateSystem(plot, KLineYAxis("price", 0.0, 100.0))

    @Test
    fun panAndScaleSessionsUpdateViewportAndKeepScaleFocalPixelStable() {
        val (store, engine) = fixture()
        val beforePan = store.snapshot.viewport!!
        assertTrue(engine.beginPan(100.0))
        assertTrue(engine.updatePan(120.0, plot))
        assertEquals(beforePan.endIndex - 2.0, store.snapshot.viewport!!.endIndex, 1e-9)
        engine.endInteraction()

        val beforeScale = store.snapshot.viewport!!
        val focalIndex = KLineXCoordinateSystem(plot, beforeScale, bars).pixelToIndex(50.0)
        assertTrue(engine.beginScale(50.0))
        assertTrue(engine.updateScale(2.0, plot))
        val afterScale = store.snapshot.viewport!!
        assertEquals(20.0, afterScale.barSpace, 1e-9)
        assertEquals(focalIndex, KLineXCoordinateSystem(plot, afterScale, bars).pixelToIndex(50.0), 1e-9)
    }

    @Test
    fun pointerDownUsesPriorityAndClickOrCrosshairPublishMarketSelections() {
        val (store, engine) = fixture()
        val overlay = KLineOverlayInstance("overlay", "horizontal_line", paneId = "price", points = listOf(KLineOverlayPoint(89_000L, 50.0)))
        store.addOverlay(overlay)
        val x = KLineXCoordinateSystem(plot, store.snapshot.viewport!!, bars)

        val resolved = engine.handlePointerDown(
            KLinePointerDownRequest(
                paneId = "price",
                pixelX = x.timestampToPixel(89_000L)!!,
                pixelY = 50.0,
                xCoordinates = x,
                yCoordinates = y,
                overlayHit = KLineOverlayHit("overlay", KLineOverlayHitType.CONTROL_POINT, 0),
                requestCrosshair = true,
                viewportGesture = KLineViewportGesture.PAN,
            ),
        )
        assertEquals(KLineInteractionIntent.OVERLAY_CONTROL_POINT, resolved)
        assertEquals(KLineInteractionState.DRAGGING_OVERLAY_POINT, store.snapshot.interactionState)
        engine.cancelInteraction()

        assertTrue(engine.showCrosshair("price", 101.0, 25.0, x, y))
        assertEquals(89_000L, store.snapshot.crosshair!!.timestamp)
        assertEquals(75.0, store.snapshot.crosshair!!.value, 1e-9)
        engine.clearCrosshair()
        assertTrue(engine.click("price", 101.0, 25.0, x, y))
        assertEquals(KLineBarSelection("price", 89_000L, 89, 75.0), store.snapshot.clickSelection)
    }

    @Test
    fun authoritativeCancelRestoresPanScaleAndPaneResizeWrites() {
        val (store, engine) = fixture()
        val initialViewport = store.snapshot.viewport!!
        assertTrue(engine.beginPan(100.0))
        engine.updatePan(120.0, plot)
        engine.cancelInteraction()
        assertEquals(initialViewport, store.snapshot.viewport)

        assertTrue(engine.beginScale(50.0))
        engine.updateScale(2.0, plot)
        engine.cancelInteraction()
        assertEquals(initialViewport, store.snapshot.viewport)

        val panes = listOf(
            com.tencent.kuiklybase.kline.pane.KLinePane("price", com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE, 0, 3.0, 20.0, yAxes = listOf(KLineYAxis("a", 0.0, 100.0))),
            com.tencent.kuiklybase.kline.pane.KLinePane("volume", com.tencent.kuiklybase.kline.pane.KLinePaneKind.INDICATOR, 1, 1.0, 20.0, yAxes = listOf(KLineYAxis("b", 0.0, 100.0))),
        )
        store.setPanes(panes)
        val layouts = com.tencent.kuiklybase.kline.pane.KLinePaneLayoutEngine.layout(panes, plot, 0.0)
        assertTrue(engine.paneResize.beginResize(0, 65.0, layouts))
        engine.paneResize.updateResize(75.0)
        engine.cancelInteraction()
        assertEquals(panes, store.snapshot.panes)
    }

    @Test
    fun lockedOverlayHitsFallThroughToPanOrCrosshair() {
        val (store, engine) = fixture()
        val locked = KLineOverlayInstance(
            "locked",
            "horizontal_line",
            paneId = "price",
            points = listOf(KLineOverlayPoint(89_000L, 50.0)),
            locked = true,
        )
        store.addOverlay(locked)
        val x = KLineXCoordinateSystem(plot, store.snapshot.viewport!!, bars)

        val panIntent = engine.handlePointerDown(
            KLinePointerDownRequest(
                paneId = "price",
                pixelX = 100.0,
                pixelY = 50.0,
                xCoordinates = x,
                yCoordinates = y,
                overlayHit = KLineOverlayHit("locked", KLineOverlayHitType.CONTROL_POINT, 0),
                viewportGesture = KLineViewportGesture.PAN,
            ),
        )
        assertEquals(KLineInteractionIntent.PAN, panIntent)
        assertEquals(KLineInteractionState.PANNING, store.snapshot.interactionState)
        engine.cancelInteraction()

        val crosshairIntent = engine.handlePointerDown(
            KLinePointerDownRequest(
                paneId = "price",
                pixelX = 100.0,
                pixelY = 25.0,
                xCoordinates = x,
                yCoordinates = y,
                overlayHit = KLineOverlayHit("locked", KLineOverlayHitType.FIGURE),
                requestCrosshair = true,
                viewportGesture = KLineViewportGesture.PAN,
            ),
        )
        assertEquals(KLineInteractionIntent.CROSSHAIR, crosshairIntent)
        assertEquals(KLineInteractionState.CROSSHAIR, store.snapshot.interactionState)
    }

    private fun fixture(): Pair<KLineStore, KLineInteractionEngine> {
        val store = KLineStore()
        store.replaceAll(bars)
        store.setViewport(KLineViewport(79.0, 99.0, 10.0, 0.0))
        return store to KLineInteractionEngine(store, KLineBuiltInOverlays.registry())
    }
}
