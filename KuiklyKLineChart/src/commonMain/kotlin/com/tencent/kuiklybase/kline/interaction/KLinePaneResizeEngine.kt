package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.pane.KLinePaneLayout
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import com.tencent.kuiklybase.kline.store.KLineStore

class KLinePaneResizeEngine(
    private val store: KLineStore,
) {
    fun beginResize(
        separatorIndex: Int,
        pointerPixel: Double,
        layouts: List<KLinePaneLayout>,
    ): Boolean {
        if (!pointerPixel.isFinite() || store.snapshot.interactionState != KLineInteractionState.IDLE) return false
        val panes = store.snapshot.panes
        if (separatorIndex < 0 || separatorIndex >= panes.lastIndex) return false
        val upper = panes[separatorIndex]
        val lower = panes[separatorIndex + 1]
        if (upper.state != KLinePaneState.NORMAL || lower.state != KLinePaneState.NORMAL) return false
        val byId = layouts.associateBy(KLinePaneLayout::paneId)
        val heights = panes.map { pane -> byId[pane.id]?.rect?.height ?: return false }
        if (heights[separatorIndex] + heights[separatorIndex + 1] < upper.minHeight + lower.minHeight) return false
        store.beginInteraction(KLineInteractionSession.ResizingPane(separatorIndex, pointerPixel, panes, heights))
        return true
    }

    fun updateResize(pointerPixel: Double): Boolean {
        if (!pointerPixel.isFinite()) return false
        val session = store.snapshot.interactionSession as? KLineInteractionSession.ResizingPane ?: return false
        val upperIndex = session.separatorIndex
        val lowerIndex = upperIndex + 1
        val upperPane = session.initialPanes[upperIndex]
        val lowerPane = session.initialPanes[lowerIndex]
        val total = session.initialHeights[upperIndex] + session.initialHeights[lowerIndex]
        val requestedUpper = session.initialHeights[upperIndex] + pointerPixel - session.startPixel
        val upperHeight = requestedUpper.coerceIn(upperPane.minHeight, total - lowerPane.minHeight)
        val heights = session.initialHeights.toMutableList()
        heights[upperIndex] = upperHeight
        heights[lowerIndex] = total - upperHeight
        val panes = session.initialPanes.mapIndexed { index, pane ->
            if (pane.state == KLinePaneState.NORMAL) {
                pane.copy(weight = (heights[index] - pane.minHeight).coerceAtLeast(1e-9))
            } else {
                pane
            }
        }
        store.setPanes(panes)
        return true
    }

    fun endResize() = store.finishInteraction()

    fun cancelResize() {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.ResizingPane ?: return
        store.setPanes(session.initialPanes)
        store.cancelInteraction()
    }
}
