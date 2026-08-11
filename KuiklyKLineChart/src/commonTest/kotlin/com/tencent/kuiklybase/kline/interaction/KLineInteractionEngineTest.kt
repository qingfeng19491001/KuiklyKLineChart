package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KLineInteractionEngineTest {
    @Test
    fun endAndCancelAdvanceInteractionRevisionExactlyOnceAndIdleCancelIsNoOp() {
        val store = KLineStore()
        store.beginInteraction(KLineInteractionSession.Panning(10.0, viewport()))
        val afterBegin = store.snapshot.interactionRevision
        store.finishInteraction()
        assertEquals(afterBegin + 1, store.snapshot.interactionRevision)
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)

        store.beginInteraction(KLineInteractionSession.Scaling(20.0, viewport()))
        val beforeCancel = store.snapshot.interactionRevision
        store.cancelInteraction()
        assertEquals(beforeCancel + 1, store.snapshot.interactionRevision)
        val afterCancel = store.snapshot.interactionRevision
        store.cancelInteraction()
        assertEquals(afterCancel, store.snapshot.interactionRevision)
    }

    @Test
    fun storeDefensivelySnapshotsPublicSessionCollections() {
        val store = KLineStore()
        val mutablePoints = mutableListOf(KLineOverlayPoint(1_000L, 10.0))
        store.beginInteraction(
            KLineInteractionSession.DrawingOverlay(
                "draft",
                "freehand",
                "price",
                KLineOverlayMagnetMode.NONE,
                mutablePoints,
            ),
        )
        mutablePoints.clear()
        assertEquals(listOf(KLineOverlayPoint(1_000L, 10.0)), (store.snapshot.interactionSession as KLineInteractionSession.DrawingOverlay).points)
        store.cancelInteraction()

        val mutableOriginal = mutableListOf(KLineOverlayPoint(2_000L, 20.0))
        store.beginInteraction(KLineInteractionSession.DraggingOverlay("overlay", mutableOriginal[0], mutableOriginal))
        mutableOriginal[0] = KLineOverlayPoint(3_000L, 30.0)
        assertEquals(listOf(KLineOverlayPoint(2_000L, 20.0)), (store.snapshot.interactionSession as KLineInteractionSession.DraggingOverlay).originalPoints)
        store.cancelInteraction()

        val mutablePanes = mutableListOf(testPane("price", 0), testPane("volume", 1))
        val mutableHeights = mutableListOf(300.0, 100.0)
        store.beginInteraction(KLineInteractionSession.ResizingPane(0, 300.0, mutablePanes, mutableHeights))
        mutablePanes.clear()
        mutableHeights[0] = 1.0
        val resizing = store.snapshot.interactionSession as KLineInteractionSession.ResizingPane
        assertEquals(listOf("price", "volume"), resizing.initialPanes.map(KLinePane::id))
        assertEquals(listOf(300.0, 100.0), resizing.initialHeights)
    }

    @Test
    fun resolverUsesTheDocumentedPriorityInsteadOfCallOrder() {
        val all = KLineInteractionCandidates(
            overlayControlPoint = true,
            overlayFigure = true,
            crosshair = true,
            paneSeparator = true,
            viewportGesture = KLineViewportGesture.PAN,
            ordinaryClick = true,
        )

        assertEquals(KLineInteractionIntent.OVERLAY_CONTROL_POINT, KLineInteractionPriorityResolver.resolve(all))
        assertEquals(KLineInteractionIntent.OVERLAY_FIGURE, KLineInteractionPriorityResolver.resolve(all.copy(overlayControlPoint = false)))
        assertEquals(KLineInteractionIntent.CROSSHAIR, KLineInteractionPriorityResolver.resolve(all.copy(overlayControlPoint = false, overlayFigure = false)))
        assertEquals(KLineInteractionIntent.PANE_SEPARATOR, KLineInteractionPriorityResolver.resolve(all.copy(overlayControlPoint = false, overlayFigure = false, crosshair = false)))
        assertEquals(KLineInteractionIntent.SCALE, KLineInteractionPriorityResolver.resolve(all.copy(overlayControlPoint = false, overlayFigure = false, crosshair = false, paneSeparator = false, viewportGesture = KLineViewportGesture.SCALE)))
        assertEquals(KLineInteractionIntent.CLICK, KLineInteractionPriorityResolver.resolve(all.copy(overlayControlPoint = false, overlayFigure = false, crosshair = false, paneSeparator = false, viewportGesture = null)))
        assertEquals(KLineInteractionIntent.NONE, KLineInteractionPriorityResolver.resolve(KLineInteractionCandidates()))
    }

    @Test
    fun crosshairKeepsItsTimestampAcrossRealtimeAndPrependThenClearsWhenDataIsRemoved() {
        val store = KLineStore()
        store.replaceAll(listOf(bar(10), bar(20), bar(30)))
        store.beginInteraction(KLineInteractionSession.Crosshair(KLineCrosshair("price", 20_000L, 1, 20.0)))

        store.applyRealtime(bar(40))
        assertEquals(KLineCrosshair("price", 20_000L, 1, 20.0), store.snapshot.crosshair)
        store.prepend(listOf(bar(0)), hasMoreBefore = false)
        assertEquals(KLineCrosshair("price", 20_000L, 2, 20.0), store.snapshot.crosshair)

        store.replaceAll(listOf(bar(0), bar(10), bar(30), bar(40)))
        assertNull(store.snapshot.crosshair)
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
    }

    @Test
    fun storeEnforcesOneInteractionAndResetClearsTransientStateButKeepsCompletedOverlay() {
        val store = KLineStore()
        val overlay = KLineOverlayInstance(
            id = "finished-1",
            templateName = "horizontal_line",
            paneId = "price",
            points = listOf(KLineOverlayPoint(1_000L, 12.0)),
        )
        store.addOverlay(overlay)
        store.selectOverlay(overlay.id)
        store.beginInteraction(
            KLineInteractionSession.Crosshair(
                KLineCrosshair(paneId = "price", timestamp = 1_000L, index = 0, value = 12.0),
            ),
        )

        assertEquals(KLineInteractionState.CROSSHAIR, store.snapshot.interactionState)
        assertFailsWith<IllegalStateException> {
            store.beginInteraction(KLineInteractionSession.Panning(startPixel = 10.0, initialViewport = viewport()))
        }

        store.cancelInteraction()
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
        store.beginInteraction(KLineInteractionSession.Panning(startPixel = 10.0, initialViewport = viewport()))
        store.reset(KLineSymbol("000001.SZ"), KLinePeriod(1, KLinePeriodUnit.DAY))

        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
        assertNull(store.snapshot.interactionSession)
        assertNull(store.snapshot.crosshair)
        assertNull(store.snapshot.selectedOverlayId)
        assertEquals(listOf(overlay), store.snapshot.overlayInstances)
        assertEquals(5L, store.snapshot.interactionRevision)
    }

    private fun bar(index: Long) = KLineBar(
        timestamp = index * 1_000L,
        open = index.toDouble(),
        high = index.toDouble() + 2.0,
        low = index.toDouble() - 2.0,
        close = index.toDouble(),
    )

    private fun viewport() = KLineViewport(0.0, 1.0, 10.0, 0.0)

    private fun testPane(id: String, order: Int) = KLinePane(
        id,
        KLinePaneKind.PRICE,
        order,
        1.0,
        10.0,
        yAxes = listOf(KLineYAxis("$id-axis", 0.0, 100.0)),
    )
}
