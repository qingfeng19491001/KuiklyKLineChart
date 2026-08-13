package com.tencent.kuiklybase.kline.render

import com.tencent.kuiklybase.kline.KLineChartMode
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.overlay.KLineOverlayConfig
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KLineChartModeTest {
    private val registry = KLineExtensionRegistry(
        templates = KLineBuiltInIndicators.templates,
        overlayTemplates = KLineBuiltInOverlays.templates,
    )

    @Test
    fun compactIsAPlannerPresetThatKeepsOnlyPriceCandlesAndCapsWork() {
        val bars = (0 until 100).map { index -> KLineBar(index.toLong(), 10.0, 12.0, 9.0, 11.0) }
        val price = KLinePane("price", KLinePaneKind.PRICE, 0, 3.0, 10.0, yAxes = listOf(KLineYAxis("price", 0.0, 20.0)))
        val volume = KLinePane("volume", KLinePaneKind.INDICATOR, 1, 1.0, 10.0, yAxes = listOf(KLineYAxis("volume", 0.0, 20.0)))
        val snapshot = KLineStoreSnapshot(
            bars = bars,
            viewport = KLineViewport(0.0, 99.0, 8.0, 0.0),
            panes = listOf(price, volume),
            indicatorInstances = listOf(KLineIndicatorInstance("ma", "ma", "price", params = listOf(5.0), precision = 2)),
            overlayInstances = listOf(KLineOverlayConfig("horizontal_line", paneId = "price", points = listOf(KLineOverlayPoint(0, 10.0))).toInstance("line")),
        )

        val plan = KLineRenderPlanner(registry).create(snapshot, KLineRect(0.0, 0.0, 800.0, 400.0), mode = KLineChartMode.COMPACT)

        assertEquals(listOf(KLinePaneKind.PRICE), plan.panes.map { it.kind })
        assertEquals(KLineVisibleRange(40, 100), plan.visibleRange)
        assertEquals(60, plan.bars.size)
        assertTrue(plan.indicators.isEmpty())
        assertTrue(plan.overlays.isEmpty())
        assertFalse(plan.features.axisLabels)
        assertFalse(plan.features.interaction)
        assertFalse(plan.features.crosshair)
        assertFalse(plan.features.tooltip)

        val sink = KLinePrimitiveListSink()
        KLineAxisRenderer().render(plan, sink)
        assertTrue(sink.primitives.any { it is KLineDrawingPrimitive.Line && it.layer == KLineRenderLayer.AXIS })
        assertFalse(sink.primitives.any { it is KLineDrawingPrimitive.Text && it.layer == KLineRenderLayer.AXIS })
    }

    @Test
    fun fullPresetPreservesExistingPlannerFeatures() {
        assertTrue(KLineChartMode.FULL.axisLabels)
        assertTrue(KLineChartMode.FULL.indicators)
        assertTrue(KLineChartMode.FULL.overlays)
        assertTrue(KLineChartMode.FULL.interaction)
        assertEquals(Int.MAX_VALUE, KLineChartMode.FULL.maxVisibleBars)
    }
}
