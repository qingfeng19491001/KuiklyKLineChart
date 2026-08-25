package com.tencent.kuiklybase.kline.pane

import com.tencent.kuiklybase.kline.layout.KLineRect

internal data class KLinePaneLayout(
    val paneId: String,
    val rect: KLineRect,
    val visible: Boolean,
) {
    fun separatorHit(pixelY: Double, tolerance: Double = 6.0): Boolean {
        val edge = rect.bottom
        return (pixelY - edge) in -tolerance..tolerance
    }
}

internal object KLinePaneLayoutEngine {
    fun layout(
        panes: List<KLinePane>,
        bounds: KLineRect,
        separatorHeight: Double = 1.0,
    ): List<KLinePaneLayout> {
        require(separatorHeight.isFinite() && separatorHeight >= 0.0) {
            "Separator height must be finite and non-negative"
        }
        require(panes.map(KLinePane::id).distinct().size == panes.size) { "Pane ids must be unique" }
        if (panes.isEmpty()) return emptyList()
        val ordered = panes.sortedWith(compareBy<KLinePane>(KLinePane::order).thenBy(KLinePane::id))
        val maximized = ordered.firstOrNull { it.state == KLinePaneState.MAXIMIZED }
        if (maximized != null) {
            val hidden = KLineRect(bounds.left, bounds.bottom, bounds.right, bounds.bottom)
            return ordered.map { pane ->
                KLinePaneLayout(
                    paneId = pane.id,
                    rect = if (pane.id == maximized.id) bounds else hidden,
                    visible = pane.id == maximized.id,
                )
            }
        }

        val separator = separatorHeight.coerceAtMost(bounds.height)
        val available = (bounds.height - separator * (ordered.size - 1)).coerceAtLeast(0.0)
        val minimumTotal = ordered.sumOf(KLinePane::minHeight)
        val heights = when {
            minimumTotal > available && minimumTotal > 0.0 ->
                ordered.map { it.minHeight / minimumTotal * available }
            else -> {
                val extra = (available - minimumTotal).coerceAtLeast(0.0)
                val normalWeight = ordered
                    .filter { it.state == KLinePaneState.NORMAL }
                    .sumOf(KLinePane::weight)
                ordered.map { pane ->
                    pane.minHeight + if (pane.state == KLinePaneState.NORMAL && normalWeight > 0.0) {
                        extra * pane.weight / normalWeight
                    } else {
                        0.0
                    }
                }
            }
        }

        var top = bounds.top
        return ordered.mapIndexed { index, pane ->
            val bottom = (top + heights[index]).coerceAtMost(bounds.bottom)
            val result = KLinePaneLayout(
                paneId = pane.id,
                rect = KLineRect(bounds.left, top, bounds.right, bottom),
                visible = true,
            )
            top = (bottom + separator).coerceAtMost(bounds.bottom)
            result
        }
    }
}
