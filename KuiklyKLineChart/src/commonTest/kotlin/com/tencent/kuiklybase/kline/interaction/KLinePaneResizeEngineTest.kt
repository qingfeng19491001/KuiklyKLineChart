package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.pane.KLinePaneLayoutEngine
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import com.tencent.kuiklybase.kline.store.KLineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KLinePaneResizeEngineTest {
    private val bounds = KLineRect(0.0, 0.0, 100.0, 400.0)

    @Test
    fun continuousResizeClampsBothDirectionsAndPreservesTotalHeight() {
        val store = KLineStore()
        store.setPanes(listOf(pane("price", 0, 3.0, 100.0), pane("volume", 1, 1.0, 60.0)))
        val engine = KLinePaneResizeEngine(store)
        val initial = KLinePaneLayoutEngine.layout(store.snapshot.panes, bounds, separatorHeight = 0.0)
        assertEquals(listOf(280.0, 120.0), initial.map { it.rect.height })

        assertTrue(engine.beginResize(0, 280.0, initial))
        assertTrue(engine.updateResize(330.0))
        assertEquals(listOf(330.0, 70.0), heights(store), tolerance = 1e-7)
        assertTrue(engine.updateResize(100.0))
        assertEquals(listOf(100.0, 300.0), heights(store), tolerance = 1e-7)
        assertTrue(engine.updateResize(250.0))
        assertEquals(listOf(250.0, 150.0), heights(store), tolerance = 1e-7)
        assertEquals(400.0, heights(store).sum(), 1e-7)
        engine.endResize()
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
    }

    @Test
    fun minimizedOrMaximizedAdjacentPaneRejectsResize() {
        listOf(KLinePaneState.MINIMIZED, KLinePaneState.MAXIMIZED).forEach { state ->
            val store = KLineStore()
            store.setPanes(listOf(pane("price", 0, 3.0, 100.0, state), pane("volume", 1, 1.0, 60.0)))
            val layouts = KLinePaneLayoutEngine.layout(store.snapshot.panes, bounds, 0.0)
            assertFalse(KLinePaneResizeEngine(store).beginResize(0, 200.0, layouts))
        }
    }

    @Test
    fun threePaneResizeSupportsNonZeroSeparatorsAndRejectsInvalidSeparatorIndices() {
        val store = KLineStore()
        store.setPanes(
            listOf(
                pane("price", 0, 3.0, 100.0),
                pane("volume", 1, 1.0, 60.0),
                pane("macd", 2, 1.0, 40.0),
            ),
        )
        val engine = KLinePaneResizeEngine(store)
        val layouts = KLinePaneLayoutEngine.layout(store.snapshot.panes, bounds, separatorHeight = 4.0)
        assertFalse(engine.beginResize(-1, 0.0, layouts))
        assertFalse(engine.beginResize(2, 0.0, layouts))

        val thirdHeight = layouts[2].rect.height
        assertTrue(engine.beginResize(0, layouts[0].rect.bottom, layouts))
        assertTrue(engine.updateResize(layouts[0].rect.bottom + 100.0))
        val resized = KLinePaneLayoutEngine.layout(store.snapshot.panes, bounds, separatorHeight = 4.0)
        assertEquals(60.0, resized[1].rect.height, 1e-7)
        assertEquals(thirdHeight, resized[2].rect.height, 1e-7)
        assertEquals(400.0, resized.sumOf { it.rect.height } + 8.0, 1e-7)
        engine.cancelResize()

        val restored = KLinePaneLayoutEngine.layout(store.snapshot.panes, bounds, separatorHeight = 4.0)
        assertTrue(engine.beginResize(1, restored[1].rect.bottom, restored))
        assertTrue(engine.updateResize(restored[1].rect.bottom - 100.0))
        val reverse = KLinePaneLayoutEngine.layout(store.snapshot.panes, bounds, separatorHeight = 4.0)
        assertEquals(60.0, reverse[1].rect.height, 1e-7)
        assertEquals(400.0, reverse.sumOf { it.rect.height } + 8.0, 1e-7)
    }

    private fun heights(store: KLineStore) =
        KLinePaneLayoutEngine.layout(store.snapshot.panes, bounds, 0.0).map { it.rect.height }

    private fun assertEquals(expected: List<Double>, actual: List<Double>, tolerance: Double) {
        expected.zip(actual).forEach { (left, right) -> assertEquals(left, right, tolerance) }
    }

    private fun pane(id: String, order: Int, weight: Double, minHeight: Double, state: KLinePaneState = KLinePaneState.NORMAL) =
        KLinePane(id, KLinePaneKind.PRICE, order, weight, minHeight, state, listOf(KLineYAxis("$id-axis", 0.0, 100.0)))
}
