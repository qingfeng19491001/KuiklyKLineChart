package com.tencent.kuiklybase.kline.render

import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.format.KLineFormatters
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.KLineChartMode
import com.tencent.kuiklybase.kline.KLinePriceStyle

data class KLineVisibleRange(val startInclusive: Int, val endExclusive: Int) {
    init { require(startInclusive >= 0 && endExclusive >= startInclusive) }
    val size: Int get() = endExclusive - startInclusive
    val isEmpty: Boolean get() = size == 0
    operator fun contains(index: Int): Boolean = index in startInclusive until endExclusive
    companion object { val EMPTY = KLineVisibleRange(0, 0) }
}

data class KLinePoint(val x: Double, val y: Double)
data class KLineRenderBar(val index: Int, val bar: KLineBar, val x: Double)
data class KLineRenderPane(
    val id: String,
    val kind: KLinePaneKind,
    val rect: KLineRect,
    val axis: KLineRenderAxis,
    val headerRect: KLineRect? = null,
    val xAxisRect: KLineRect? = null,
)
data class KLineRenderAxis(val id: String, val minValue: Double, val maxValue: Double, val valueToPixelScale: Double, val valueToPixelOffset: Double) {
    fun valueToPixel(value: Double): Double = value * valueToPixelScale + valueToPixelOffset
}
class KLineIndicatorRenderSeries(val paneId: String, val key: String, val type: KLineIndicatorFigureType, val color: String, val latestValue: Double?, points: List<KLinePoint>) {
    val points: List<KLinePoint> = frozenList(points)
}

sealed interface KLineOverlayRenderFigure {
    val style: KLineOverlayFigureStyle
    data class Line(val start: KLinePoint, val end: KLinePoint, override val style: KLineOverlayFigureStyle) : KLineOverlayRenderFigure
    class Polyline(points: List<KLinePoint>, override val style: KLineOverlayFigureStyle) : KLineOverlayRenderFigure {
        val points: List<KLinePoint> = frozenList(points)
    }
    data class Text(val anchor: KLinePoint, val text: String, override val style: KLineOverlayFigureStyle) : KLineOverlayRenderFigure
}

data class KLineCrosshairRenderData(val paneId: String, val point: KLinePoint, val xLabel: String, val yLabel: String)
class KLineTooltipRenderData(val bounds: KLineRect, lines: List<String>) { val lines: List<String> = frozenList(lines) }

data class KLineRenderFeatures(
    val axisLabels: Boolean,
    val priceAnnotations: Boolean,
    val interaction: Boolean,
    val crosshair: Boolean,
    val tooltip: Boolean,
) {
    companion object {
        internal fun from(mode: KLineChartMode) = KLineRenderFeatures(
            mode.axisLabels,
            mode == KLineChartMode.FULL,
            mode.interaction,
            mode.crosshair,
            mode.tooltip,
        )
    }
}

class KLineRenderPlan internal constructor(
    val bounds: KLineRect,
    val visibleRange: KLineVisibleRange,
    val contentRange: KLineVisibleRange = visibleRange,
    bars: List<KLineRenderBar>,
    panes: List<KLineRenderPane>,
    indicators: List<KLineIndicatorRenderSeries>,
    overlays: List<KLineOverlayRenderFigure>,
    val crosshair: KLineCrosshairRenderData?,
    val tooltip: KLineTooltipRenderData?,
    val theme: KLineTheme,
    val formatters: KLineFormatters,
    val barSpace: Double,
    val priceStyle: KLinePriceStyle = KLinePriceStyle.CANDLE,
    val selectedBar: KLineRenderBar? = null,
    internal val clickSelectedBar: KLineRenderBar? = null,
    val features: KLineRenderFeatures = KLineRenderFeatures.from(KLineChartMode.FULL),
) {
    val bars: List<KLineRenderBar> = frozenList(bars)
    val panes: List<KLineRenderPane> = frozenList(panes)
    val indicators: List<KLineIndicatorRenderSeries> = frozenList(indicators)
    val overlays: List<KLineOverlayRenderFigure> = frozenList(overlays)
}

internal fun <T> frozenList(source: Collection<T>): List<T> = FrozenList(source)
private class FrozenList<T>(source: Collection<T>) : AbstractList<T>() {
    private val data: Array<Any?> = source.toTypedArray()
    override val size: Int get() = data.size
    @Suppress("UNCHECKED_CAST") override fun get(index: Int): T = data[index] as T
}
