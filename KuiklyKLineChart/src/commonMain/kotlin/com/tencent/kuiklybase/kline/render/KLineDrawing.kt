package com.tencent.kuiklybase.kline.render

import com.tencent.kuiklybase.kline.layout.KLineRect

enum class KLineRenderLayer(val order: Int) { GRID(0), CANDLE(1), INDICATOR(2), OVERLAY(3), AXIS(4), CROSSHAIR(5), TOOLTIP(6) }

data class KLineStroke(val color: String, val width: Double, val dash: List<Double> = emptyList())

sealed interface KLineDrawingPrimitive {
    val layer: KLineRenderLayer
    data class Line(override val layer: KLineRenderLayer, val start: KLinePoint, val end: KLinePoint, val stroke: KLineStroke) : KLineDrawingPrimitive
    data class Rect(override val layer: KLineRenderLayer, val bounds: KLineRect, val fillColor: String, val stroke: KLineStroke? = null, val cornerRadius: Double = 0.0) : KLineDrawingPrimitive
    class Polyline(override val layer: KLineRenderLayer, points: List<KLinePoint>, val stroke: KLineStroke) : KLineDrawingPrimitive {
        val points: List<KLinePoint> = frozenList(points)
    }
    data class Circle(override val layer: KLineRenderLayer, val center: KLinePoint, val radius: Double, val color: String) : KLineDrawingPrimitive
    data class Candle(override val layer: KLineRenderLayer = KLineRenderLayer.CANDLE, val x: Double, val openY: Double, val highY: Double, val lowY: Double, val closeY: Double, val bodyWidth: Double, val color: String, val wickWidth: Double) : KLineDrawingPrimitive
    data class Text(override val layer: KLineRenderLayer, val text: String, val bounds: KLineRect, val color: String, val textSize: Double) : KLineDrawingPrimitive
}

fun interface KLinePrimitiveSink { fun draw(primitive: KLineDrawingPrimitive) }
class KLinePrimitiveListSink : KLinePrimitiveSink {
    private val mutablePrimitives = mutableListOf<KLineDrawingPrimitive>()
    val primitives: List<KLineDrawingPrimitive> get() = frozenList(mutablePrimitives)
    override fun draw(primitive: KLineDrawingPrimitive) { mutablePrimitives += primitive }
}

fun interface KLineRenderer { fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) }

class KLineGridRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        val stroke = KLineStroke(plan.theme.grid.color, plan.theme.grid.lineWidth, frozenList(plan.theme.grid.lineDash))
        plan.panes.forEach { pane ->
            for (step in 0..4) { val y = pane.rect.top + pane.rect.height * step / 4.0; sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.GRID, KLinePoint(pane.rect.left, y), KLinePoint(pane.rect.right, y), stroke)) }
        }
    }
}

class KLineCandleRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        val pane = plan.panes.firstOrNull { it.kind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE } ?: return
        plan.bars.forEach { entry ->
            val bar = entry.bar
            val color = when { bar.close > bar.open -> plan.theme.candle.riseColor; bar.close < bar.open -> plan.theme.candle.fallColor; else -> plan.theme.candle.unchangedColor }
            sink.draw(KLineDrawingPrimitive.Candle(x = entry.x, openY = pane.axis.valueToPixel(bar.open), highY = pane.axis.valueToPixel(bar.high), lowY = pane.axis.valueToPixel(bar.low), closeY = pane.axis.valueToPixel(bar.close), bodyWidth = plan.barSpace * plan.theme.candle.bodyWidthRatio, color = color, wickWidth = plan.theme.candle.wickWidth))
        }
    }
}

class KLineIndicatorRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        plan.indicators.forEach { series ->
            val stroke = KLineStroke(series.color, plan.theme.indicator.lineWidth)
            when (series.type) {
                com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType.POINT -> series.points.forEach { sink.draw(KLineDrawingPrimitive.Circle(KLineRenderLayer.INDICATOR, it, 2.0, series.color)) }
                com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType.BAR -> series.points.forEach { point -> sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.INDICATOR, point, KLinePoint(point.x, plan.panes.first { it.id == series.paneId }.rect.bottom), stroke)) }
                else -> if (series.points.size >= 2) sink.draw(KLineDrawingPrimitive.Polyline(KLineRenderLayer.INDICATOR, series.points, stroke))
            }
        }
    }
}

class KLineOverlayRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) { plan.overlays.forEach { figure ->
        val stroke = KLineStroke(figure.style.color, figure.style.lineWidth, frozenList(figure.style.lineDash))
        when (figure) {
            is KLineOverlayRenderFigure.Line -> sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.OVERLAY, figure.start, figure.end, stroke))
            is KLineOverlayRenderFigure.Polyline -> sink.draw(KLineDrawingPrimitive.Polyline(KLineRenderLayer.OVERLAY, figure.points, stroke))
            is KLineOverlayRenderFigure.Text -> sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.OVERLAY, figure.text, KLineRect(figure.anchor.x, figure.anchor.y, figure.anchor.x + figure.text.length * figure.style.textSize * .6, figure.anchor.y + figure.style.textSize), figure.style.color, figure.style.textSize))
        }
    } }
}

data class KLineTextSize(val width: Double, val height: Double)
fun interface KLineTextMeasurer { fun measure(text: String, textSize: Double): KLineTextSize }
object KLineApproximateTextMeasurer : KLineTextMeasurer { override fun measure(text: String, textSize: Double) = KLineTextSize(text.length * textSize * .55, textSize) }

class KLineRenderCaches {
    internal val texts = mutableMapOf<Pair<String, Double>, KLineTextSize>()
    internal val ticks = mutableMapOf<AxisTickKey, List<AxisTick>>()
    var textHits: Int = 0; internal set
    var textMisses: Int = 0; internal set
    var tickHits: Int = 0; internal set
    var tickMisses: Int = 0; internal set
}
internal data class AxisTickKey(val min: Double, val max: Double, val top: Double, val bottom: Double, val formatterHash: Int)
internal data class AxisTick(val value: Double, val y: Double, val text: String)

class KLineAxisRenderer(private val caches: KLineRenderCaches = KLineRenderCaches(), private val measurer: KLineTextMeasurer = KLineApproximateTextMeasurer) : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        plan.panes.forEach { pane ->
            sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.AXIS, KLinePoint(pane.rect.right, pane.rect.top), KLinePoint(pane.rect.right, pane.rect.bottom), KLineStroke(plan.theme.axis.lineColor, 1.0)))
            if (!plan.features.axisLabels) return@forEach
            val key = AxisTickKey(pane.axis.minValue, pane.axis.maxValue, pane.rect.top, pane.rect.bottom, plan.formatters.hashCode())
            val ticks = caches.ticks[key]?.also { caches.tickHits++ } ?: buildTicks(plan, pane).also { caches.ticks[key] = it; caches.tickMisses++ }
            var previousBottom = Double.NEGATIVE_INFINITY
            ticks.forEach { tick ->
                val textKey = tick.text to plan.theme.axis.textSize
                val size = caches.texts[textKey]?.also { caches.textHits++ } ?: measurer.measure(tick.text, plan.theme.axis.textSize).also { caches.texts[textKey] = it; caches.textMisses++ }
                val top = tick.y - size.height / 2.0
                if (top >= previousBottom && top >= pane.rect.top && top + size.height <= pane.rect.bottom) {
                    val label = KLineRect((pane.rect.right - size.width - plan.theme.axis.tickLength).coerceAtLeast(pane.rect.left), top, (pane.rect.right - plan.theme.axis.tickLength).coerceAtLeast(pane.rect.left), top + size.height)
                    sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.AXIS, tick.text, label, plan.theme.axis.textColor, plan.theme.axis.textSize)); previousBottom = label.bottom
                }
            }
        }
    }
    private fun buildTicks(plan: KLineRenderPlan, pane: KLineRenderPane): List<AxisTick> = (0..4).map { step ->
        val value = pane.axis.maxValue - (pane.axis.maxValue - pane.axis.minValue) * step / 4.0
        AxisTick(value, pane.axis.valueToPixel(value), plan.formatters.formatPrice(value))
    }
}

class KLineCrosshairRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) { if (!plan.features.crosshair) return; val data = plan.crosshair ?: return; val pane = plan.panes.firstOrNull { it.id == data.paneId } ?: return; val stroke = KLineStroke(plan.theme.crosshair.lineColor, plan.theme.crosshair.lineWidth)
        sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.CROSSHAIR, KLinePoint(pane.rect.left, data.point.y), KLinePoint(pane.rect.right, data.point.y), stroke)); sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.CROSSHAIR, KLinePoint(data.point.x, pane.rect.top), KLinePoint(data.point.x, pane.rect.bottom), stroke))
    }
}

class KLineTooltipRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) { if (!plan.features.tooltip) return; val tooltip = plan.tooltip ?: return; sink.draw(KLineDrawingPrimitive.Rect(KLineRenderLayer.TOOLTIP, tooltip.bounds, plan.theme.tooltip.backgroundColor, cornerRadius = plan.theme.tooltip.cornerRadius)); val lineHeight = plan.theme.tooltip.textSize + 2.0; tooltip.lines.forEachIndexed { index, line -> val top = tooltip.bounds.top + plan.theme.tooltip.padding + index * lineHeight; if (top + plan.theme.tooltip.textSize <= tooltip.bounds.bottom) sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.TOOLTIP, line, KLineRect(tooltip.bounds.left + plan.theme.tooltip.padding, top, tooltip.bounds.right - plan.theme.tooltip.padding, top + plan.theme.tooltip.textSize), if (index == 0) plan.theme.tooltip.titleColor else plan.theme.tooltip.textColor, plan.theme.tooltip.textSize)) } }
}

class KLineRenderPipeline(
    renderers: List<KLineRenderer> = listOf(KLineGridRenderer(), KLineCandleRenderer(), KLineIndicatorRenderer(), KLineOverlayRenderer(), KLineAxisRenderer(), KLineCrosshairRenderer(), KLineTooltipRenderer()),
) {
    private val renderers: List<KLineRenderer> = frozenList(renderers)
    fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) { renderers.forEach { it.render(plan, sink) } }
}
