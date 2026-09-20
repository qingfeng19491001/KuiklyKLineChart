package com.tencent.kuiklybase.kline.render

import com.tencent.kuiklybase.kline.layout.KLineRect

internal enum class KLineRenderLayer(val order: Int) { GRID(0), CANDLE(1), INDICATOR(2), PRICE_ANNOTATION(3), OVERLAY(4), AXIS(5), CROSSHAIR(6), TOOLTIP(7) }

internal data class KLineStroke(val color: String, val width: Double, val dash: List<Double> = emptyList())

internal sealed interface KLineDrawingPrimitive {
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

internal fun interface KLinePrimitiveSink { fun draw(primitive: KLineDrawingPrimitive) }
internal class KLinePrimitiveListSink : KLinePrimitiveSink {
    private val mutablePrimitives = mutableListOf<KLineDrawingPrimitive>()
    val primitives: List<KLineDrawingPrimitive> get() = frozenList(mutablePrimitives)
    override fun draw(primitive: KLineDrawingPrimitive) { mutablePrimitives += primitive }
}

internal fun interface KLineRenderer { fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) }

internal class KLineGridRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        if (!plan.features.axisLabels) return
        val stroke = KLineStroke(plan.theme.grid.color, plan.theme.grid.lineWidth, frozenList(plan.theme.grid.lineDash))
        plan.panes.forEach { pane ->
            for (step in 0..4) { val y = pane.rect.top + pane.rect.height * step / 4.0; sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.GRID, KLinePoint(pane.rect.left, y), KLinePoint(pane.rect.right, y), stroke)) }
        }
    }
}

internal class KLineCandleRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        val pane = plan.panes.firstOrNull { it.kind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE } ?: return
        val closePoints = plan.bars.map { entry -> KLinePoint(entry.x, pane.axis.valueToPixel(entry.bar.close)) }
        val lineColor = plan.theme.indicator.palette.first()
        when (plan.priceStyle) {
            com.tencent.kuiklybase.kline.KLinePriceStyle.LINE -> {
                if (closePoints.size >= 2) {
                    sink.draw(KLineDrawingPrimitive.Polyline(KLineRenderLayer.CANDLE, closePoints, KLineStroke(lineColor, 1.5)))
                }
                closePoints.lastOrNull()?.let { last ->
                    sink.draw(KLineDrawingPrimitive.Circle(KLineRenderLayer.CANDLE, last, 2.5, lineColor))
                }
                return
            }
            com.tencent.kuiklybase.kline.KLinePriceStyle.AREA -> {
                val baseline = pane.rect.bottom
                val fill = colorWithRgbAlpha(lineColor, "4D")
                plan.bars.forEach { entry ->
                    val closeY = pane.axis.valueToPixel(entry.bar.close)
                    val half = plan.barSpace / 2.0
                    sink.draw(
                        KLineDrawingPrimitive.Rect(
                            KLineRenderLayer.CANDLE,
                            KLineRect(entry.x - half, minOf(closeY, baseline), entry.x + half, maxOf(closeY, baseline)),
                            fill,
                        ),
                    )
                }
                if (closePoints.size >= 2) {
                    sink.draw(KLineDrawingPrimitive.Polyline(KLineRenderLayer.CANDLE, closePoints, KLineStroke(lineColor, 1.5)))
                }
                closePoints.lastOrNull()?.let { last ->
                    sink.draw(KLineDrawingPrimitive.Circle(KLineRenderLayer.CANDLE, last, 2.5, lineColor))
                }
                return
            }
            else -> Unit
        }
        plan.bars.forEach { entry ->
            val bar = entry.bar
            val rising = bar.close > bar.open
            val falling = bar.close < bar.open
            val color = when {
                rising -> plan.theme.candle.riseColor
                falling -> plan.theme.candle.fallColor
                else -> plan.theme.candle.unchangedColor
            }
            val openY = pane.axis.valueToPixel(bar.open)
            val highY = pane.axis.valueToPixel(bar.high)
            val lowY = pane.axis.valueToPixel(bar.low)
            val closeY = pane.axis.valueToPixel(bar.close)
            val bodyWidth = plan.barSpace * plan.theme.candle.bodyWidthRatio
            when (plan.priceStyle) {
                com.tencent.kuiklybase.kline.KLinePriceStyle.OHLC -> {
                    val half = bodyWidth / 2.0
                    val wick = KLineStroke(color, plan.theme.candle.wickWidth)
                    sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.CANDLE, KLinePoint(entry.x, highY), KLinePoint(entry.x, lowY), wick))
                    sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.CANDLE, KLinePoint(entry.x - half, openY), KLinePoint(entry.x, openY), wick))
                    sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.CANDLE, KLinePoint(entry.x, closeY), KLinePoint(entry.x + half, closeY), wick))
                }
                else -> {
                    val hollow = when (plan.priceStyle) {
                        com.tencent.kuiklybase.kline.KLinePriceStyle.CANDLE_HOLLOW -> true
                        com.tencent.kuiklybase.kline.KLinePriceStyle.CANDLE_UP_STROKE -> rising
                        com.tencent.kuiklybase.kline.KLinePriceStyle.CANDLE_DOWN_STROKE -> falling
                        else -> false
                    }
                    if (hollow) {
                        val halfWidth = bodyWidth / 2.0
                        val top = minOf(openY, closeY)
                        val rawBottom = maxOf(openY, closeY)
                        val bottom = if (rawBottom - top < 1.0) top + 1.0 else rawBottom
                        sink.draw(
                            KLineDrawingPrimitive.Line(
                                KLineRenderLayer.CANDLE,
                                KLinePoint(entry.x, highY),
                                KLinePoint(entry.x, lowY),
                                KLineStroke(color, plan.theme.candle.wickWidth),
                            ),
                        )
                        sink.draw(
                            KLineDrawingPrimitive.Rect(
                                KLineRenderLayer.CANDLE,
                                KLineRect(entry.x - halfWidth, top, entry.x + halfWidth, bottom),
                                fillColor = "#00000000",
                                stroke = KLineStroke(color, plan.theme.candle.wickWidth),
                            ),
                        )
                    } else {
                        sink.draw(
                            KLineDrawingPrimitive.Candle(
                                x = entry.x,
                                openY = openY,
                                highY = highY,
                                lowY = lowY,
                                closeY = closeY,
                                bodyWidth = bodyWidth,
                                color = color,
                                wickWidth = plan.theme.candle.wickWidth,
                            ),
                        )
                    }
                }
            }
            if (plan.clickSelectedBar?.index == entry.index) {
                val halfWidth = bodyWidth / 2.0
                val top = minOf(openY, closeY)
                val rawBottom = maxOf(openY, closeY)
                val bottom = if (rawBottom - top < 1.0) top + 1.0 else rawBottom
                sink.draw(
                    KLineDrawingPrimitive.Rect(
                        KLineRenderLayer.CANDLE,
                        KLineRect(entry.x - halfWidth, top, entry.x + halfWidth, bottom),
                        fillColor = "#00000000",
                        stroke = KLineStroke("#FFFFFF", 2.0),
                    ),
                )
                sink.draw(
                    KLineDrawingPrimitive.Rect(
                        KLineRenderLayer.CANDLE,
                        KLineRect(entry.x - halfWidth, top, entry.x + halfWidth, bottom),
                        fillColor = "#00000000",
                        stroke = KLineStroke(color, 1.5),
                    ),
                )
            }
        }
    }
}

internal fun colorWithRgbAlpha(color: String, alphaHex: String): String {
    val raw = color.removePrefix("#")
    return if (raw.length == 6) "#$raw$alphaHex" else color
}

internal class KLineIndicatorRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        plan.indicators.forEach { series ->
            val stroke = KLineStroke(series.color, plan.theme.indicator.lineWidth)
            when (series.type) {
                com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType.POINT -> series.points.forEach { sink.draw(KLineDrawingPrimitive.Circle(KLineRenderLayer.INDICATOR, it, 2.0, series.color)) }
                com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType.BAR -> {
                    val pane = plan.panes.first { it.id == series.paneId }
                    val baseline = pane.axis.valueToPixel(0.0).coerceIn(pane.rect.top, pane.rect.bottom)
                    series.points.forEach { point -> sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.INDICATOR, point, KLinePoint(point.x, baseline), stroke)) }
                }
                else -> if (series.points.size >= 2) sink.draw(KLineDrawingPrimitive.Polyline(KLineRenderLayer.INDICATOR, series.points, stroke))
            }
        }
    }
}

internal class KLinePriceAnnotationRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        if (!plan.features.priceAnnotations) return
        val pane = plan.panes.firstOrNull { it.kind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE } ?: return
        val visibleBars = plan.bars.filter { it.index in plan.contentRange }
        if (visibleBars.isEmpty()) return
        val highest = visibleBars.maxBy { it.bar.high }
        val lowest = visibleBars.minBy { it.bar.low }
        val latest = visibleBars.maxBy { it.index }
        val textSize = plan.theme.axis.textSize
        fun extreme(entry: KLineRenderBar, value: Double, above: Boolean) {
            val y = pane.axis.valueToPixel(value)
            val labelWidth = 56.0
            val preferRight = entry.x < (pane.rect.left + pane.rect.right) / 2.0
            val left = (if (preferRight) entry.x + 5.0 else entry.x - labelWidth - 5.0).coerceIn(pane.rect.left, pane.rect.right - labelWidth)
            val top = (if (above) y - textSize - 3.0 else y + 3.0).coerceIn(pane.rect.top, pane.rect.bottom - textSize)
            sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.PRICE_ANNOTATION, KLinePoint(entry.x, y), KLinePoint(if (preferRight) entry.x + 5.0 else entry.x - 5.0, y), KLineStroke(plan.theme.axis.textColor, 1.0)))
            sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.PRICE_ANNOTATION, plan.formatters.formatPrice(value), KLineRect(left, top, left + labelWidth, top + textSize), plan.theme.axis.textColor, textSize))
        }
        extreme(highest, highest.bar.high, above = true)
        extreme(lowest, lowest.bar.low, above = false)
        val latestY = pane.axis.valueToPixel(latest.bar.close)
        val latestColor = when {
            latest.bar.close > latest.bar.open -> plan.theme.candle.riseColor
            latest.bar.close < latest.bar.open -> plan.theme.candle.fallColor
            else -> plan.theme.candle.unchangedColor
        }
        val labelWidth = 56.0
        sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.PRICE_ANNOTATION, KLinePoint(pane.rect.left, latestY), KLinePoint(pane.rect.right, latestY), KLineStroke(latestColor, 1.0, listOf(4.0, 3.0))))
        sink.draw(KLineDrawingPrimitive.Rect(KLineRenderLayer.PRICE_ANNOTATION, KLineRect(pane.rect.right - labelWidth, latestY - textSize / 2.0 - 2.0, pane.rect.right, latestY + textSize / 2.0 + 2.0), latestColor, cornerRadius = 2.0))
        sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.PRICE_ANNOTATION, plan.formatters.formatPrice(latest.bar.close), KLineRect(pane.rect.right - labelWidth, latestY - textSize / 2.0, pane.rect.right - 3.0, latestY + textSize / 2.0), "#FFFFFF", textSize))
    }
}

internal class KLinePaneHeaderRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        plan.panes.forEach { pane ->
            val header = pane.headerRect ?: return@forEach
            val series = plan.indicators.filter { it.paneId == pane.id }
            val values = series.joinToString("  ") { item ->
                "${item.key}:${plan.formatters.formatIndicatorValue(item.latestValue, 2)}"
            }
            val selected = plan.selectedBar?.bar
            val price = if (pane.kind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE && selected != null) {
                "O:${plan.formatters.formatPrice(selected.open)}  H:${plan.formatters.formatPrice(selected.high)}  L:${plan.formatters.formatPrice(selected.low)}  C:${plan.formatters.formatPrice(selected.close)}"
            } else ""
            val text = listOf(price, values).filter { it.isNotBlank() }.joinToString("  ")
            if (text.isBlank()) return@forEach
            sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.AXIS, text, KLineRect(header.left + 5.0, header.top + 3.0, header.right - 5.0, header.bottom), plan.theme.indicator.textColor, plan.theme.axis.textSize))
        }
    }
}

internal class KLineXAxisRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        if (!plan.features.axisLabels || plan.bars.isEmpty() || plan.panes.isEmpty()) return
        val pricePane = plan.panes.firstOrNull { it.kind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE } ?: return
        val axisRect = pricePane.xAxisRect ?: return
        val desiredTicks = minOf(5, plan.bars.size)
        if (desiredTicks < 2) return
        val indices = (0 until desiredTicks).map { tick -> tick * (plan.bars.lastIndex) / (desiredTicks - 1) }.distinct()
        var previousRight = Double.NEGATIVE_INFINITY
        val baseTimestamp = normalizedMillis(plan.bars.first().bar.timestamp)
        indices.forEach { localIndex ->
            val entry = plan.bars[localIndex]
            val label = plan.formatters.formatAxisTick(baseTimestamp, normalizedMillis(entry.bar.timestamp))
            val width = label.length * plan.theme.axis.textSize * .55
            val left = (entry.x - width / 2.0).coerceIn(plan.bounds.left, plan.bounds.right - width)
            if (left < previousRight + 6.0) return@forEach
            sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.AXIS, KLinePoint(entry.x, pricePane.rect.top), KLinePoint(entry.x, plan.panes.last().rect.bottom), KLineStroke(plan.theme.grid.color, plan.theme.grid.lineWidth)))
            sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.AXIS, label, KLineRect(left, axisRect.top + 2.0, left + width, axisRect.bottom), plan.theme.axis.textColor, plan.theme.axis.textSize))
            previousRight = left + width
        }
    }

    private fun normalizedMillis(timestamp: Long): Long = if (timestamp in -9_999_999_999L..9_999_999_999L) timestamp * 1000L else timestamp
}

internal class KLineOverlayRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) { plan.overlays.forEach { figure ->
        val stroke = KLineStroke(figure.style.color, figure.style.lineWidth, frozenList(figure.style.lineDash))
        when (figure) {
            is KLineOverlayRenderFigure.Line -> sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.OVERLAY, figure.start, figure.end, stroke))
            is KLineOverlayRenderFigure.Polyline -> sink.draw(KLineDrawingPrimitive.Polyline(KLineRenderLayer.OVERLAY, figure.points, stroke))
            is KLineOverlayRenderFigure.Text -> sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.OVERLAY, figure.text, KLineRect(figure.anchor.x, figure.anchor.y, figure.anchor.x + figure.text.length * figure.style.textSize * .6, figure.anchor.y + figure.style.textSize), figure.style.color, figure.style.textSize))
        }
    } }
}

internal data class KLineTextSize(val width: Double, val height: Double)
internal fun interface KLineTextMeasurer { fun measure(text: String, textSize: Double): KLineTextSize }
internal object KLineApproximateTextMeasurer : KLineTextMeasurer { override fun measure(text: String, textSize: Double) = KLineTextSize(text.length * textSize * .55, textSize) }

internal class KLineRenderCaches {
    internal val texts = mutableMapOf<Pair<String, Double>, KLineTextSize>()
    internal val ticks = mutableMapOf<AxisTickKey, List<AxisTick>>()
    var textHits: Int = 0; internal set
    var textMisses: Int = 0; internal set
    var tickHits: Int = 0; internal set
    var tickMisses: Int = 0; internal set
}
internal data class AxisTickKey(val min: Double, val max: Double, val top: Double, val bottom: Double, val formatterHash: Int)
internal data class AxisTick(val value: Double, val y: Double, val text: String)

internal class KLineAxisRenderer(private val caches: KLineRenderCaches = KLineRenderCaches(), private val measurer: KLineTextMeasurer = KLineApproximateTextMeasurer) : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) {
        if (!plan.features.axisLabels) return
        plan.panes.forEach { pane ->
            sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.AXIS, KLinePoint(pane.rect.right, pane.rect.top), KLinePoint(pane.rect.right, pane.rect.bottom), KLineStroke(plan.theme.axis.lineColor, 1.0)))
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

internal class KLineCrosshairRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) { if (!plan.features.crosshair) return; val data = plan.crosshair ?: return; val pane = plan.panes.firstOrNull { it.id == data.paneId } ?: return; val stroke = KLineStroke(plan.theme.crosshair.lineColor, plan.theme.crosshair.lineWidth)
        sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.CROSSHAIR, KLinePoint(pane.rect.left, data.point.y), KLinePoint(pane.rect.right, data.point.y), stroke)); sink.draw(KLineDrawingPrimitive.Line(KLineRenderLayer.CROSSHAIR, KLinePoint(data.point.x, plan.panes.first().rect.top), KLinePoint(data.point.x, plan.panes.last().rect.bottom), stroke))
    }
}

internal class KLineTooltipRenderer : KLineRenderer {
    override fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) { if (!plan.features.tooltip) return; val tooltip = plan.tooltip ?: return; sink.draw(KLineDrawingPrimitive.Rect(KLineRenderLayer.TOOLTIP, tooltip.bounds, plan.theme.tooltip.backgroundColor, cornerRadius = plan.theme.tooltip.cornerRadius)); val lineHeight = plan.theme.tooltip.textSize + 2.0; tooltip.lines.forEachIndexed { index, line -> val top = tooltip.bounds.top + plan.theme.tooltip.padding + index * lineHeight; if (top + plan.theme.tooltip.textSize <= tooltip.bounds.bottom) sink.draw(KLineDrawingPrimitive.Text(KLineRenderLayer.TOOLTIP, line, KLineRect(tooltip.bounds.left + plan.theme.tooltip.padding, top, tooltip.bounds.right - plan.theme.tooltip.padding, top + plan.theme.tooltip.textSize), if (index == 0) plan.theme.tooltip.titleColor else plan.theme.tooltip.textColor, plan.theme.tooltip.textSize)) } }
}

internal class KLineRenderPipeline(
    renderers: List<KLineRenderer> = listOf(KLineGridRenderer(), KLineCandleRenderer(), KLineIndicatorRenderer(), KLinePriceAnnotationRenderer(), KLineOverlayRenderer(), KLinePaneHeaderRenderer(), KLineAxisRenderer(), KLineXAxisRenderer(), KLineCrosshairRenderer(), KLineTooltipRenderer()),
) {
    private val renderers: List<KLineRenderer> = frozenList(renderers)
    fun render(plan: KLineRenderPlan, sink: KLinePrimitiveSink) { renderers.forEach { it.render(plan, sink) } }
}
