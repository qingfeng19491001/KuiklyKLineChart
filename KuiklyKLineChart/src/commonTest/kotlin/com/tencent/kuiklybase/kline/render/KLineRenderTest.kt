package com.tencent.kuiklybase.kline.render

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureResult
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorResult
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorSeries
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KLineRenderTest {
    @Test
    fun visibleRangeClampsAndAddsOverscan() {
        assertEquals(KLineVisibleRange(0, 5), KLineVisibleRangeResolver.resolve(KLineViewport(-2.0, 2.2, 10.0, 0.0), 5, 2))
        assertEquals(KLineVisibleRange(8, 10), KLineVisibleRangeResolver.resolve(KLineViewport(9.2, 14.0, 10.0, 0.0), 10, 1))
        assertEquals(KLineVisibleRange.EMPTY, KLineVisibleRangeResolver.resolve(KLineViewport(0.0, 2.0, 10.0, 0.0), 0, 1))
    }

    @Test
    fun plannerOnlyReadsVisibleBarsAndFreezesThem() {
        val source = MutableList(100_000) { bar(it) }
        val tracked = TrackingList(source)
        val planner = planner()
        val plan = planner.create(snapshot(tracked, KLineViewport(50_000.0, 50_009.0, 10.0, 0.0)), BOUNDS, overscanBars = 2)
        assertEquals(KLineVisibleRange(49_998, 50_012), plan.visibleRange)
        assertTrue(tracked.reads <= 20, "read ${tracked.reads} bars for a 14-bar render window")
        val frozenClose = plan.bars.first().bar.close
        source[49_998] = bar(49_998, close = -1.0)
        assertEquals(frozenClose, plan.bars.first().bar.close)
        assertFalse(plan.bars is MutableList<*>)
    }

    @Test
    fun pipelineUsesFixedLayerOrderAndEveryRendererEmitsItsPrimitive() {
        val snapshot = snapshot(
            listOf(bar(0), bar(1), bar(2)),
            KLineViewport(0.0, 2.0, 40.0, 0.0),
            overlay = KLineOverlayInstance(
                id = "line", templateName = "segment", paneId = "price",
                points = listOf(KLineOverlayPoint(0, 10.0), KLineOverlayPoint(2, 12.0)),
            ),
            crosshair = true,
            selection = true,
            indicator = true,
        )
        val plan = planner().create(snapshot, BOUNDS)
        val sink = KLinePrimitiveListSink()
        KLineRenderPipeline().render(plan, sink)
        val layers = sink.primitives.map(KLineDrawingPrimitive::layer)
        assertEquals(layers.sortedBy(KLineRenderLayer::order), layers)
        KLineRenderLayer.entries.forEach { layer -> assertTrue(layer in layers, "missing $layer") }
        assertTrue(sink.primitives.any { it is KLineDrawingPrimitive.Candle })
        assertTrue(sink.primitives.any { it is KLineDrawingPrimitive.Line && it.layer == KLineRenderLayer.OVERLAY })
        assertTrue(sink.primitives.any { it is KLineDrawingPrimitive.Text && it.layer == KLineRenderLayer.TOOLTIP })
    }

    @Test
    fun axisLabelsDoNotOverlapAndCachesHit() {
        val measurer = CountingMeasurer()
        val caches = KLineRenderCaches()
        val renderer = KLineAxisRenderer(caches, measurer)
        val plan = planner().create(snapshot((0..20).map(::bar), KLineViewport(0.0, 20.0, 10.0, 0.0)), BOUNDS)
        val first = KLinePrimitiveListSink()
        renderer.render(plan, first)
        val labels = first.primitives.filterIsInstance<KLineDrawingPrimitive.Text>()
        assertTrue(labels.zipWithNext().all { (a, b) -> a.bounds.bottom <= b.bounds.top || b.bounds.bottom <= a.bounds.top })
        val measured = measurer.calls
        val tickMisses = caches.tickMisses
        renderer.render(plan, KLinePrimitiveListSink())
        assertEquals(measured, measurer.calls)
        assertEquals(tickMisses, caches.tickMisses)
        assertTrue(caches.textHits > 0)
        assertTrue(caches.tickHits > 0)
    }

    @Test
    fun renderersConsumePlanWithoutChangingStoreSnapshot() {
        val mutableBars = mutableListOf(bar(0), bar(1))
        val snapshot = snapshot(mutableBars, KLineViewport(0.0, 1.0, 20.0, 0.0))
        val revisions = listOf(snapshot.dataRevision, snapshot.viewportRevision, snapshot.paneRevision)
        val plan = planner().create(snapshot, BOUNDS)
        KLineRenderPipeline().render(plan, KLinePrimitiveListSink())
        assertEquals(revisions, listOf(snapshot.dataRevision, snapshot.viewportRevision, snapshot.paneRevision))
        assertEquals(2, snapshot.bars.size)
    }

    private fun planner() = KLineRenderPlanner(
        KLineExtensionRegistry(KLineBuiltInIndicators.templates, KLineBuiltInOverlays.templates),
    )

    private fun snapshot(
        bars: List<KLineBar>, viewport: KLineViewport,
        overlay: KLineOverlayInstance? = null, crosshair: Boolean = false, selection: Boolean = false, indicator: Boolean = false,
    ) = KLineStoreSnapshot(
        bars = bars,
        viewport = viewport,
        panes = listOf(KLinePane("price", KLinePaneKind.PRICE, 0, 1.0, 10.0, yAxes = listOf(KLineYAxis("price", 0.0, 100.0)))),
        overlayInstances = listOfNotNull(overlay),
        indicatorInstances = if (indicator) listOf(KLineIndicatorInstance("ma", "MA", "price", listOf(2.0), 2)) else emptyList(),
        indicatorResults = if (indicator) mapOf("ma" to KLineIndicatorResult("MA", KLineIndicatorSeries.PRICE, listOf(KLineIndicatorFigureResult("ma", KLineIndicatorFigureType.LINE, listOf(10.0, 11.0, 12.0))))) else emptyMap(),
        crosshair = if (crosshair) com.tencent.kuiklybase.kline.interaction.KLineCrosshair("price", 1, 1, 11.0) else null,
        clickSelection = if (selection) com.tencent.kuiklybase.kline.interaction.KLineBarSelection("price", 1, 1, 11.0) else null,
    )

    private fun bar(index: Int, close: Double = 10.0 + index % 10) = KLineBar(index.toLong(), close - 1, close + 1, close - 2, close, 100.0 + index)

    private class TrackingList(private val delegate: List<KLineBar>) : AbstractList<KLineBar>() {
        var reads = 0
        override val size get() = delegate.size
        override fun get(index: Int): KLineBar { reads++; return delegate[index] }
    }

    private class CountingMeasurer : KLineTextMeasurer {
        var calls = 0
        override fun measure(text: String, textSize: Double): KLineTextSize { calls++; return KLineTextSize(text.length * textSize * .5, textSize) }
    }

    companion object { val BOUNDS = KLineRect(0.0, 0.0, 240.0, 160.0) }
}
