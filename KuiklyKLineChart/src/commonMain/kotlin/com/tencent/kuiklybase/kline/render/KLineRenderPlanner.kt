package com.tencent.kuiklybase.kline.render

import com.tencent.kuiklybase.kline.KLineChartMode
import com.tencent.kuiklybase.kline.KLinePriceStyle
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.axis.KLineYAxisMode
import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineOverlayEngine
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigure
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
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

    fun create(
        snapshot: KLineStoreSnapshot,
        bounds: KLineRect,
        overscanBars: Int = 1,
        mode: KLineChartMode = KLineChartMode.FULL,
        priceStyle: KLinePriceStyle = KLinePriceStyle.CANDLE,
        additionalOverlays: List<KLineOverlayInstance> = emptyList(),
    ): KLineRenderPlan {
        val viewport = snapshot.viewport ?: KLineViewport(0.0, (snapshot.bars.size - 1).coerceAtLeast(0).toDouble(), 10.0, 0.0)
        val effectiveOverscan = if (mode == KLineChartMode.FULL) overscanBars else minOf(overscanBars, mode.overscanBars)
        val contentRange = KLineVisibleRangeResolver.resolve(viewport, snapshot.bars.size, 0)
        val resolvedRange = KLineVisibleRangeResolver.resolve(viewport, snapshot.bars.size, effectiveOverscan)
        val range = if (resolvedRange.size <= mode.maxVisibleBars) resolvedRange else {
            KLineVisibleRange(resolvedRange.endExclusive - mode.maxVisibleBars, resolvedRange.endExclusive)
        }
        val renderBars = ArrayList<KLineRenderBar>(range.size)
        for (index in range.startInclusive until range.endExclusive) {
            val bar = snapshot.bars[index]
            renderBars += KLineRenderBar(index, bar.copy(), bounds.left + (index - viewport.startIndex) * viewport.barSpace)
        }
        val selectedPanes = if (mode.secondaryPanes) snapshot.panes else snapshot.panes.filter { it.kind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE }.take(1)
        val layouts = KLinePaneLayoutEngine.layout(selectedPanes, bounds).associateBy { it.paneId }
        val panes = selectedPanes.mapNotNull { pane ->
            val layout = layouts[pane.id]?.takeIf { it.visible } ?: return@mapNotNull null
            val headerHeight = if (mode == KLineChartMode.FULL) minOf(18.0, layout.rect.height * 0.18) else 0.0
            val xAxisHeight = if (mode.axisLabels && pane.kind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE) {
                minOf(snapshot.theme.axis.textSize + 6.0, layout.rect.height * 0.16)
            } else 0.0
            val headerRect = if (headerHeight > 0.0) {
                KLineRect(layout.rect.left, layout.rect.top, layout.rect.right, layout.rect.top + headerHeight)
            } else null
            val plotRect = KLineRect(layout.rect.left, headerRect?.bottom ?: layout.rect.top, layout.rect.right, layout.rect.bottom - xAxisHeight)
            val xAxisRect = if (xAxisHeight > 0.0) KLineRect(layout.rect.left, plotRect.bottom, layout.rect.right, layout.rect.bottom) else null
            val axis = expandedAxis(pane.yAxes.first(), pane.id, pane.kind, snapshot, contentRange, renderBars.filter { it.index in contentRange })
            val y = KLineYCoordinateSystem(plotRect, axis)
            KLineRenderPane(pane.id, pane.kind, plotRect, KLineRenderAxis(axis.id, axis.minValue, axis.maxValue,
                y.valueToPixel(axis.minValue + 1.0) - y.valueToPixel(axis.minValue), y.valueToPixel(axis.minValue) - axis.minValue * (y.valueToPixel(axis.minValue + 1.0) - y.valueToPixel(axis.minValue))))
                .copy(headerRect = headerRect, xAxisRect = xAxisRect)
        }
        val paneById = panes.associateBy(KLineRenderPane::id)
        val selectedIndex = snapshot.crosshair?.index ?: snapshot.clickSelection?.index ?: (range.endExclusive - 1)
        val indicators = snapshot.indicatorInstances.asSequence().filter { mode.indicators && it.visible }.mapNotNull { instance ->
            val result = snapshot.indicatorResults[instance.id] ?: return@mapNotNull null
            val pane = paneById[instance.paneId] ?: return@mapNotNull null
            result.figures.mapIndexed { figureIndex, figure ->
                val points = ArrayList<KLinePoint>(range.size)
                for (index in range.startInclusive until minOf(range.endExclusive, figure.values.size)) {
                    val value = figure.values[index] ?: continue
                    points += KLinePoint(bounds.left + (index - viewport.startIndex) * viewport.barSpace, pane.axis.valueToPixel(value))
                }
                val latestValue = if (selectedIndex in figure.values.indices) figure.values[selectedIndex] else null
                KLineIndicatorRenderSeries(instance.paneId, figure.key, figure.type, snapshot.theme.indicator.palette[figureIndex % snapshot.theme.indicator.palette.size], latestValue, points)
            }
        }.flatten().toList()
        val overlays = (snapshot.overlayInstances + additionalOverlays).asSequence().filter { mode.overlays && it.visible && paneById.containsKey(it.paneId) }.flatMap { instance ->
            val pane = requireNotNull(paneById[instance.paneId])
            overlayEngine.createFigures(instance).mapNotNull { resolveOverlay(it, pane, snapshot, viewport, bounds) }.asSequence()
        }.toList()
        val selectionCrosshair = snapshot.clickSelection?.takeIf { selection ->
            mode.crosshair && selection.index in range && paneById[selection.paneId]?.kind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE
        }?.let { selection ->
            val pane = requireNotNull(paneById[selection.paneId])
            val bar = snapshot.bars[selection.index]
            val x = bounds.left + (selection.index - viewport.startIndex) * viewport.barSpace
            KLineCrosshairRenderData(selection.paneId, KLinePoint(x, pane.axis.valueToPixel(bar.close)), snapshot.formatters.formatDate(bar.timestamp), snapshot.formatters.formatPrice(bar.close))
        }
        val crosshair = selectionCrosshair ?: snapshot.crosshair?.takeIf { mode.crosshair && it.index in range && paneById.containsKey(it.paneId) }?.let {
            val pane = requireNotNull(paneById[it.paneId])
            val x = bounds.left + (it.index - viewport.startIndex) * viewport.barSpace
            KLineCrosshairRenderData(it.paneId, KLinePoint(x, pane.axis.valueToPixel(it.value)), snapshot.formatters.formatDate(it.timestamp), snapshot.formatters.formatPrice(it.value))
        }
        val tooltip = snapshot.clickSelection?.takeIf { mode.tooltip && it.index in range }?.let { selection ->
            val local = renderBars[selection.index - range.startInclusive].bar
            val pane = paneById[selection.paneId] ?: return@let null
            val anchorX = bounds.left + (selection.index - viewport.startIndex) * viewport.barSpace
            val anchorY = pane.axis.valueToPixel(local.close)
            val text = buildString {
                append(snapshot.formatters.formatDate(local.timestamp))
                append("  O:").append(snapshot.formatters.formatPrice(local.open))
                append(" H:").append(snapshot.formatters.formatPrice(local.high))
                append(" L:").append(snapshot.formatters.formatPrice(local.low))
                append(" C:").append(snapshot.formatters.formatPrice(local.close))
                append("  VOL:").append(snapshot.formatters.formatIndicatorValue(local.volume, 2))
            }
            val tooltipWidth = (text.length * snapshot.theme.tooltip.textSize * .55 + snapshot.theme.tooltip.padding * 2.0)
                .coerceIn(72.0, (bounds.width - 16.0).coerceAtLeast(72.0))
            val tooltipHeight = snapshot.theme.tooltip.textSize + snapshot.theme.tooltip.padding * 2.0 + 4.0
            val rightCandidate = anchorX + 8.0
            val left = if (rightCandidate + tooltipWidth <= bounds.right - 8.0) {
                rightCandidate
            } else {
                (anchorX - 8.0 - tooltipWidth).coerceAtLeast(bounds.left + 8.0)
            }
            val top = (anchorY - 28.0).coerceIn(bounds.top + 4.0, (bounds.bottom - tooltipHeight - 4.0).coerceAtLeast(bounds.top + 4.0))
            KLineTooltipRenderData(KLineRect(left, top, left + tooltipWidth, top + tooltipHeight), listOf(text))
        }
        val selectedBar = renderBars.firstOrNull { it.index == selectedIndex }
        val clickSelectedBar = snapshot.clickSelection?.let { selection ->
            renderBars.firstOrNull { it.index == selection.index }
        }
        return KLineRenderPlan(bounds, range, contentRange, renderBars, panes, indicators, overlays, crosshair, tooltip, snapshot.theme, snapshot.formatters, viewport.barSpace, priceStyle, selectedBar, clickSelectedBar, KLineRenderFeatures.from(mode))
    }

    private fun expandedAxis(
        configured: KLineYAxis,
        paneId: String,
        paneKind: com.tencent.kuiklybase.kline.pane.KLinePaneKind,
        snapshot: KLineStoreSnapshot,
        range: KLineVisibleRange,
        renderBars: List<KLineRenderBar>,
    ): KLineYAxis {
        if (!configured.autoScale) return configured
        var dataMin = Double.POSITIVE_INFINITY
        var dataMax = Double.NEGATIVE_INFINITY
        fun include(value: Double?) {
            if (value == null || !value.isFinite()) return
            dataMin = minOf(dataMin, value)
            dataMax = maxOf(dataMax, value)
        }
        if (paneKind == com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE) {
            renderBars.forEach { entry ->
                val bar = entry.bar
                include(bar.low)
                include(bar.high)
            }
        }
        snapshot.indicatorInstances.asSequence()
            .filter { it.visible && it.paneId == paneId }
            .forEach { instance ->
                snapshot.indicatorResults[instance.id]?.figures?.forEach { figure ->
                    if (figure.type == KLineIndicatorFigureType.BAR && configured.mode == KLineYAxisMode.NORMAL) include(0.0)
                    for (index in range.startInclusive until minOf(range.endExclusive, figure.values.size)) include(figure.values[index])
                }
            }
        if (!dataMin.isFinite() || !dataMax.isFinite()) return configured
        var minValue = dataMin
        var maxValue = dataMax
        if (minValue == maxValue) {
            val delta = if (minValue == 0.0) 1.0 else kotlin.math.abs(minValue) * 0.05
            minValue -= delta
            maxValue += delta
        } else {
            val padding = (maxValue - minValue) * 0.05
            if (minValue != 0.0 || configured.mode != KLineYAxisMode.NORMAL) minValue -= padding
            maxValue += padding
        }
        if (configured.mode == KLineYAxisMode.LOGARITHMIC && minValue <= 0.0) minValue = configured.minValue
        return configured.copy(minValue = minValue, maxValue = maxValue)
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
