package com.tencent.kuiklybase.kline.render

import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineOverlayEngine
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigure
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.pane.KLinePaneLayoutEngine
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import kotlin.math.ceil
import kotlin.math.floor

object KLineVisibleRangeResolver {
    fun resolve(viewport: KLineViewport, itemCount: Int, overscanBars: Int = 1): KLineVisibleRange {
        require(itemCount >= 0) { "Item count must be non-negative" }
        require(overscanBars >= 0) { "Overscan must be non-negative" }
        if (itemCount == 0) return KLineVisibleRange.EMPTY
        val start = (floor(viewport.startIndex).toInt() - overscanBars).coerceIn(0, itemCount)
        val end = (ceil(viewport.endIndex).toInt() + 1 + overscanBars).coerceIn(start, itemCount)
        return KLineVisibleRange(start, end)
    }
}

class KLineRenderPlanner(registry: KLineExtensionRegistry) {
    private val overlayEngine = KLineOverlayEngine(registry)

    fun create(snapshot: KLineStoreSnapshot, bounds: KLineRect, overscanBars: Int = 1): KLineRenderPlan {
        val viewport = snapshot.viewport ?: KLineViewport(0.0, (snapshot.bars.size - 1).coerceAtLeast(0).toDouble(), 10.0, 0.0)
        val range = KLineVisibleRangeResolver.resolve(viewport, snapshot.bars.size, overscanBars)
        val layouts = KLinePaneLayoutEngine.layout(snapshot.panes, bounds).associateBy { it.paneId }
        val panes = snapshot.panes.mapNotNull { pane ->
            val layout = layouts[pane.id]?.takeIf { it.visible } ?: return@mapNotNull null
            val axis = pane.yAxes.first()
            val y = KLineYCoordinateSystem(layout.rect, axis)
            KLineRenderPane(pane.id, pane.kind, layout.rect, KLineRenderAxis(axis.id, axis.minValue, axis.maxValue,
                y.valueToPixel(axis.minValue + 1.0) - y.valueToPixel(axis.minValue), y.valueToPixel(axis.minValue) - axis.minValue * (y.valueToPixel(axis.minValue + 1.0) - y.valueToPixel(axis.minValue))))
        }
        val paneById = panes.associateBy(KLineRenderPane::id)
        val renderBars = ArrayList<KLineRenderBar>(range.size)
        for (index in range.startInclusive until range.endExclusive) {
            val bar = snapshot.bars[index]
            renderBars += KLineRenderBar(index, bar.copy(), bounds.left + (index - viewport.startIndex) * viewport.barSpace)
        }
        val indicators = snapshot.indicatorInstances.asSequence().filter { it.visible }.mapNotNull { instance ->
            val result = snapshot.indicatorResults[instance.id] ?: return@mapNotNull null
            val pane = paneById[instance.paneId] ?: return@mapNotNull null
            result.figures.mapIndexed { figureIndex, figure ->
                val points = ArrayList<KLinePoint>(range.size)
                for (index in range.startInclusive until minOf(range.endExclusive, figure.values.size)) {
                    val value = figure.values[index] ?: continue
                    points += KLinePoint(bounds.left + (index - viewport.startIndex) * viewport.barSpace, pane.axis.valueToPixel(value))
                }
                KLineIndicatorRenderSeries(instance.paneId, figure.type, snapshot.theme.indicator.palette[figureIndex % snapshot.theme.indicator.palette.size], points)
            }
        }.flatten().toList()
        val overlays = snapshot.overlayInstances.asSequence().filter { it.visible && paneById.containsKey(it.paneId) }.flatMap { instance ->
            val pane = requireNotNull(paneById[instance.paneId])
            overlayEngine.createFigures(instance).mapNotNull { resolveOverlay(it, pane, snapshot, viewport, bounds) }.asSequence()
        }.toList()
        val crosshair = snapshot.crosshair?.takeIf { it.index in range && paneById.containsKey(it.paneId) }?.let {
            val pane = requireNotNull(paneById[it.paneId])
            val x = bounds.left + (it.index - viewport.startIndex) * viewport.barSpace
            KLineCrosshairRenderData(it.paneId, KLinePoint(x, pane.axis.valueToPixel(it.value)), snapshot.formatters.formatDate(it.timestamp), snapshot.formatters.formatPrice(it.value))
        }
        val tooltip = snapshot.clickSelection?.takeIf { it.index in range }?.let { selection ->
            val local = renderBars[selection.index - range.startInclusive].bar
            KLineTooltipRenderData(KLineRect(bounds.left + 8.0, bounds.top + 8.0, minOf(bounds.right, bounds.left + 128.0), minOf(bounds.bottom, bounds.top + 86.0)), listOf(
                snapshot.formatters.formatDate(local.timestamp), "O ${snapshot.formatters.formatPrice(local.open)}", "H ${snapshot.formatters.formatPrice(local.high)}",
                "L ${snapshot.formatters.formatPrice(local.low)}", "C ${snapshot.formatters.formatPrice(local.close)}"))
        }
        return KLineRenderPlan(bounds, range, renderBars, panes, indicators, overlays, crosshair, tooltip, snapshot.theme, snapshot.formatters, viewport.barSpace)
    }

    private fun resolveOverlay(figure: KLineOverlayFigure, pane: KLineRenderPane, snapshot: KLineStoreSnapshot, viewport: KLineViewport, bounds: KLineRect): KLineOverlayRenderFigure? {
        fun point(value: KLineOverlayPoint): KLinePoint? = timestampIndex(snapshot, value.timestamp)?.let { KLinePoint(bounds.left + (it - viewport.startIndex) * viewport.barSpace, pane.axis.valueToPixel(value.value)) }
        fun style(value: KLineOverlayFigureStyle) = value.copy(lineDash = value.lineDash.toList())
        return when (figure) {
            is KLineOverlayFigure.HorizontalLine -> KLineOverlayRenderFigure.Line(KLinePoint(pane.rect.left, pane.axis.valueToPixel(figure.value)), KLinePoint(pane.rect.right, pane.axis.valueToPixel(figure.value)), style(figure.style))
            is KLineOverlayFigure.VerticalLine -> timestampIndex(snapshot, figure.timestamp)?.let { index -> val x = bounds.left + (index - viewport.startIndex) * viewport.barSpace; KLineOverlayRenderFigure.Line(KLinePoint(x, pane.rect.top), KLinePoint(x, pane.rect.bottom), style(figure.style)) }
            is KLineOverlayFigure.Segment -> point(figure.start)?.let { a -> point(figure.end)?.let { b -> KLineOverlayRenderFigure.Line(a, b, style(figure.style)) } }
            is KLineOverlayFigure.Ray -> point(figure.start)?.let { a -> point(figure.through)?.let { b -> extended(a, b, pane.rect, false, style(figure.style)) } }
            is KLineOverlayFigure.InfiniteLine -> point(figure.start)?.let { a -> point(figure.through)?.let { b -> extended(a, b, pane.rect, true, style(figure.style)) } }
            is KLineOverlayFigure.Polyline -> figure.points.mapNotNull(::point).takeIf { it.size >= 2 }?.let { KLineOverlayRenderFigure.Polyline(it, style(figure.style)) }
            is KLineOverlayFigure.Text -> point(figure.anchor)?.let { KLineOverlayRenderFigure.Text(it, figure.text, style(figure.style)) }
        }
    }

    private fun timestampIndex(snapshot: KLineStoreSnapshot, timestamp: Long): Int? {
        var low = 0; var high = snapshot.bars.lastIndex
        while (low <= high) { val mid = (low + high) ushr 1; val value = snapshot.bars[mid].timestamp; if (value < timestamp) low = mid + 1 else if (value > timestamp) high = mid - 1 else return mid }
        return null
    }

    private fun extended(a: KLinePoint, b: KLinePoint, rect: KLineRect, both: Boolean, style: KLineOverlayFigureStyle): KLineOverlayRenderFigure.Line? {
        val dx = b.x - a.x; if (dx == 0.0) return KLineOverlayRenderFigure.Line(KLinePoint(a.x, rect.top), KLinePoint(a.x, rect.bottom), style)
        fun at(x: Double) = KLinePoint(x, a.y + (x - a.x) * (b.y - a.y) / dx)
        return KLineOverlayRenderFigure.Line(if (both) at(rect.left) else a, at(rect.right), style)
    }
}
