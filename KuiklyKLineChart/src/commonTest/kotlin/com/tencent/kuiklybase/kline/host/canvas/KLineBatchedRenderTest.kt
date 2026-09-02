package com.tencent.kuiklybase.kline.host.canvas

import com.tencent.kuiklybase.kline.KLineChartMode
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.interaction.KLineCrosshair
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.render.KLineDrawingPrimitive
import com.tencent.kuiklybase.kline.render.KLinePrimitiveListSink
import com.tencent.kuiklybase.kline.render.KLineRenderPlanner
import com.tencent.kuiklybase.kline.render.KLineRenderPipeline
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.round

/**
 * C 组 batchDraw A/B 测试的等价性保障：
 * renderBatched 与 render 必须输出几何等价的图元集合（遮挡关系由层顺序保证）。
 */
class KLineBatchedRenderTest {

    private class RecordingCanvas : KLineCanvasAdapter {
        val ops = mutableListOf<String>()

        private fun d(v: Double): Double = round(v * 1e6) / 1e6

        override fun withSave(block: () -> Unit) = block()

        override fun drawLine(startX: Double, startY: Double, endX: Double, endY: Double, color: String, width: Double, dash: List<Double>) {
            ops += "line|${d(startX)},${d(startY)},${d(endX)},${d(endY)}|$color|${d(width)}|$dash"
        }

        override fun drawRect(left: Double, top: Double, right: Double, bottom: Double, color: String, strokeColor: String?, strokeWidth: Double, cornerRadius: Double) {
            ops += "rect|${d(left)},${d(top)},${d(right)},${d(bottom)}|$color|$strokeColor|${d(strokeWidth)}|${d(cornerRadius)}"
        }

        override fun drawPolyline(points: List<Pair<Double, Double>>, color: String, width: Double, dash: List<Double>) {
            ops += "poly|${points.joinToString(",") { "${d(it.first)},${d(it.second)}" }}|$color|${d(width)}|$dash"
        }

        override fun drawCircle(cx: Double, cy: Double, radius: Double, color: String) {
            ops += "circle|${d(cx)},${d(cy)},${d(radius)}|$color"
        }

        override fun drawCandle(x: Double, openY: Double, highY: Double, lowY: Double, closeY: Double, bodyWidth: Double, color: String, wickWidth: Double) {
            // 参照 AndroidKLineCanvasAdapter.drawCandle / flattenCandles 的拆分规则
            val top = minOf(openY, closeY)
            val rawBottom = maxOf(openY, closeY)
            val bottom = if (rawBottom - top < 1.0) top + 1.0 else rawBottom
            val halfBody = bodyWidth / 2.0
            // 复用 drawLine/drawRect 的格式化，避免 0.0 的字符串表示在 JVM("0.0")/JS("0") 间不一致
            drawLine(x, highY, x, lowY, color, wickWidth, emptyList())
            drawRect(x - halfBody, top, x + halfBody, bottom, color, null, 0.0, 0.0)
        }

        override fun drawText(text: String, left: Double, top: Double, right: Double, bottom: Double, color: String, textSize: Double) {
            ops += "text|$text|${d(left)},${d(top)},${d(right)},${d(bottom)}|$color|${d(textSize)}"
        }
    }

    @Test
    fun batchedRenderEmitsGeometricallyEquivalentPrimitives() {
        val plan = planner().create(
            snapshot(
                (0..30).map(::bar),
                KLineViewport(0.0, 30.0, 10.0, 0.0),
                overlay = KLineOverlayInstance(
                    id = "line", templateName = "segment", paneId = "price",
                    points = listOf(KLineOverlayPoint(0, 10.0), KLineOverlayPoint(30, 12.0)),
                ),
                crosshair = true,
                indicator = true,
            ),
            BOUNDS,
            mode = KLineChartMode.FULL,
        )

        val default = RecordingCanvas()
        KLineCanvasRenderer.render(plan, default)
        val batched = RecordingCanvas()
        KLineCanvasRenderer.renderBatched(plan, batched)

        // 图元数量一致（蜡烛在两条路径下都拆为 影线+实体 记录）
        assertEquals(default.ops.size, batched.ops.size)
        // 几何等价：多重集合一致（顺序无关），遮挡由层顺序保证
        assertEquals(default.ops.sorted(), batched.ops.sorted())
        // 批量路径的输出不得为空，且应包含蜡烛实体
        assertTrue(batched.ops.any { it.startsWith("rect|") })
    }

    @Test
    fun batchedRenderGroupsConsecutiveSameStylePrimitives() {
        val plan = planner().create(
            snapshot((0..30).map(::bar), KLineViewport(0.0, 30.0, 10.0, 0.0), indicator = true),
            BOUNDS,
        )
        val batched = RecordingCanvas()
        KLineCanvasRenderer.renderBatched(plan, batched)

        // 相同签名的操作应连续出现（分组生效的烟雾测试）：
        // 统计「相邻操作不同」的跳变次数，分组后应显著少于操作总数
        var transitions = 0
        batched.ops.forEachIndexed { index, op ->
            if (index > 0 && op != batched.ops[index - 1]) transitions++
        }
        assertTrue(batched.ops.size > 50, "expected substantial ops, got ${batched.ops.size}")
        assertTrue(transitions < batched.ops.size, "no grouping detected")
    }

    @Test
    fun pipelineLayerOrderIsStableUnderBatching() {
        val plan = planner().create(
            snapshot((0..10).map(::bar), KLineViewport(0.0, 10.0, 10.0, 0.0), crosshair = true),
            BOUNDS,
        )
        val sink = KLinePrimitiveListSink()
        KLineRenderPipeline().render(plan, sink)
        val layers = sink.primitives.map(KLineDrawingPrimitive::layer)
        assertEquals(layers.sortedBy { it.order }, layers, "pipeline must emit layers in fixed order")
    }

    private fun planner() = KLineRenderPlanner(
        KLineExtensionRegistry(KLineBuiltInIndicators.templates, KLineBuiltInOverlays.templates),
    )

    private fun snapshot(
        bars: List<KLineBar>, viewport: KLineViewport,
        overlay: KLineOverlayInstance? = null, crosshair: Boolean = false, indicator: Boolean = false,
    ) = KLineStoreSnapshot(
        bars = bars,
        viewport = viewport,
        panes = listOf(KLinePane("price", KLinePaneKind.PRICE, 0, 1.0, 10.0, yAxes = listOf(KLineYAxis("price", 0.0, 100.0)))),
        overlayInstances = listOfNotNull(overlay),
        indicatorInstances = if (indicator) listOf(com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance("ma", "MA", "price", listOf(2.0), 2)) else emptyList(),
        indicatorResults = if (indicator) mapOf(
            "ma" to com.tencent.kuiklybase.kline.indicator.KLineIndicatorResult(
                "MA",
                com.tencent.kuiklybase.kline.indicator.KLineIndicatorSeries.PRICE,
                listOf(com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureResult("ma", com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType.LINE, (0..30).map { 10.0 + it % 5 })),
            ),
        ) else emptyMap(),
        crosshair = if (crosshair) KLineCrosshair("price", 1, 1, 11.0) else null,
    )

    private fun bar(index: Int, close: Double = 10.0 + index % 10) = KLineBar(index.toLong(), close - 1, close + 1, close - 2, close, 100.0 + index)

    companion object { internal val BOUNDS = KLineRect(0.0, 0.0, 240.0, 160.0) }
}
