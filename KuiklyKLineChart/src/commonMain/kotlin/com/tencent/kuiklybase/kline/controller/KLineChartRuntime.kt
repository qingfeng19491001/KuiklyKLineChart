package com.tencent.kuiklybase.kline.controller

import com.tencent.kuiklybase.kline.data.KLineDataSession
import com.tencent.kuiklybase.kline.data.exactTimestampIndex
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.interaction.KLineInteractionEngine
import com.tencent.kuiklybase.kline.interaction.KLineInteractionSession
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.viewport.KLineViewportConfig
import com.tencent.kuiklybase.kline.viewport.KLineViewportEngine
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.coroutines.cancellation.CancellationException

internal class KLineChartRuntime(
    private val store: KLineStore,
    private val dataSession: KLineDataSession,
    private val viewportConfig: KLineViewportConfig = KLineViewportConfig(),
) : KLineChartControllerTarget {
    internal val interactionEngine = KLineInteractionEngine(store, store.extensionRegistry, viewportConfig)
    private var plotRect: KLineRect? = null
    private val storeSubscription = store.observe(::onStoreChanged)

    fun updatePlotRect(rect: KLineRect) {
        plotRect = rect
        val current = store.snapshot
        val next = current.viewport?.let { viewport ->
            KLineViewportEngine.resize(
                viewport = viewport,
                dataCount = current.bars.size,
                plotWidth = rect.width,
                config = viewportConfig,
            )
        } ?: current.bars.takeIf { it.isNotEmpty() }?.let {
            KLineViewportEngine.initial(
                dataCount = current.bars.size,
                plotWidth = rect.width,
                config = viewportConfig,
            )
        }
        store.setViewport(next)
    }

    fun dispose() {
        interactionEngine.cancelInteraction()
        store.resetInteractionState()
        storeSubscription.cancel()
        dataSession.close()
    }

    override fun execute(command: KLineControllerCommand) {
        when (command) {
            is KLineControllerCommand.SetMarket -> {
                interactionEngine.cancelInteraction()
                dataSession.setMarket(command.symbol, command.period)
            }
            KLineControllerCommand.ScrollToLatest -> updateViewport { viewport, rect ->
                KLineViewportEngine.scrollToLatest(
                    viewport = viewport,
                    dataCount = store.snapshot.bars.size,
                    plotWidth = rect.width,
                    config = viewportConfig,
                )
            }
            is KLineControllerCommand.ScrollToTimestamp -> scrollToTimestamp(command.timestamp)
            is KLineControllerCommand.ScrollByBars -> updateViewport { viewport, rect ->
                KLineViewportEngine.panByPixels(
                    viewport = viewport,
                    pixelDelta = -command.count * viewport.barSpace,
                    dataCount = store.snapshot.bars.size,
                    plotWidth = rect.width,
                    config = viewportConfig,
                )
            }
            is KLineControllerCommand.Zoom -> zoomAtCenter(command.factor)
            is KLineControllerCommand.ZoomAtTimestamp -> zoomAtTimestamp(command.factor, command.timestamp)
            KLineControllerCommand.ResetViewport -> resetViewport()
            is KLineControllerCommand.SetPane -> setPane(command.pane)
            is KLineControllerCommand.RemovePane -> removePane(command.paneId)
            is KLineControllerCommand.MovePane -> movePane(command.paneId, command.index)
            is KLineControllerCommand.SetPaneState -> setPaneState(command.paneId, command.state)
            is KLineControllerCommand.AddIndicator -> store.setIndicator(command.instance)
            is KLineControllerCommand.UpdateIndicator -> store.setIndicator(command.instance)
            is KLineControllerCommand.RemoveIndicator -> store.removeIndicator(command.instanceId)
            is KLineControllerCommand.SetTheme -> store.setTheme(command.theme)
            is KLineControllerCommand.SetFormatters -> store.setFormatters(command.formatters)
            is KLineControllerCommand.RestoreState -> restoreState(command.state)
            is KLineControllerCommand.CreateOverlay -> store.addOverlay(command.instance)
            is KLineControllerCommand.UpdateOverlay -> store.updateOverlay(command.instance)
            is KLineControllerCommand.RemoveOverlay -> store.removeOverlay(command.instanceId)
            is KLineControllerCommand.RemoveOverlayGroup -> store.removeOverlayGroup(command.groupId)
            is KLineControllerCommand.BeginOverlay -> interactionEngine.overlay.beginOverlay(
                command.templateName, command.paneId, command.magnetMode, command.draftId,
                command.groupId, command.locked,
            )
            KLineControllerCommand.CancelInteraction -> interactionEngine.cancelInteraction()
            KLineControllerCommand.ClearCrosshair -> interactionEngine.clearCrosshair()
            KLineControllerCommand.DeleteSelectedOverlay -> interactionEngine.overlay.deleteSelectedOverlay()
        }
    }

    override fun exportState(): KLineChartState {
        val current = store.snapshot
        val session = current.interactionSession
        val durableViewport = when (session) {
            is KLineInteractionSession.Panning -> session.initialViewport
            is KLineInteractionSession.Scaling -> session.initialViewport
            else -> current.viewport
        }
        val durablePanes = (session as? KLineInteractionSession.ResizingPane)?.initialPanes ?: current.panes
        val draggedOverlayId: String?
        val originalPoints = when (session) {
            is KLineInteractionSession.DraggingOverlay -> {
                draggedOverlayId = session.instanceId
                session.originalPoints
            }
            is KLineInteractionSession.DraggingOverlayPoint -> {
                draggedOverlayId = session.instanceId
                session.originalPoints
            }
            else -> {
                draggedOverlayId = null
                null
            }
        }
        val durableOverlays = if (draggedOverlayId == null || originalPoints == null) current.overlayInstances else {
            current.overlayInstances.map { instance ->
                if (instance.id == draggedOverlayId) instance.copy(points = originalPoints) else instance
            }
        }
        return KLineChartState(
            viewport = durableViewport?.copy(),
            panes = durablePanes,
            indicatorInstances = current.indicatorInstances,
            overlayInstances = durableOverlays,
            theme = current.theme,
            formatters = current.formatters,
        )
    }

    private fun restoreState(state: KLineChartState) {
        try {
            store.restoreState(state)
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            store.reportRestoreFailure(cause)
        }
    }

    private fun scrollToTimestamp(timestamp: Long) {
        val current = store.snapshot
        val rect = plotRect ?: return
        val viewport = current.viewport ?: return
        val index = KLineXCoordinateSystem(rect, viewport, current.bars)
            .timestampToIndex(timestamp)
            ?.toDouble() ?: return
        store.setViewport(
            KLineViewportEngine.scrollToIndex(
                viewport = viewport,
                index = index,
                anchorRatio = 0.5,
                dataCount = current.bars.size,
                plotWidth = rect.width,
                config = viewportConfig,
            ),
        )
    }

    private fun zoomAtCenter(factor: Double) {
        updateViewport { viewport, rect ->
            KLineViewportEngine.zoomAtPixel(
                viewport = viewport,
                factor = factor,
                focalPixel = rect.left + rect.width / 2.0,
                plotLeft = rect.left,
                plotWidth = rect.width,
                dataCount = store.snapshot.bars.size,
                config = viewportConfig,
            )
        }
    }

    private fun zoomAtTimestamp(
        factor: Double,
        timestamp: Long,
    ) {
        val current = store.snapshot
        val rect = plotRect ?: return
        val viewport = current.viewport ?: return
        val index = KLineXCoordinateSystem(rect, viewport, current.bars)
            .timestampToIndex(timestamp)
            ?.toDouble() ?: return
        val centered = KLineViewportEngine.scrollToIndex(
            viewport = viewport,
            index = index,
            anchorRatio = 0.5,
            dataCount = current.bars.size,
            plotWidth = rect.width,
            config = viewportConfig,
        )
        store.setViewport(
            KLineViewportEngine.zoomAtPixel(
                viewport = centered,
                factor = factor,
                focalPixel = rect.left + rect.width / 2.0,
                plotLeft = rect.left,
                plotWidth = rect.width,
                dataCount = current.bars.size,
                config = viewportConfig,
            ),
        )
    }

    private fun resetViewport() {
        interactionEngine.cancelInteraction()
        store.resetInteractionState()
        val rect = plotRect ?: return
        store.setViewport(
            KLineViewportEngine.initial(
                dataCount = store.snapshot.bars.size,
                plotWidth = rect.width,
                config = viewportConfig,
            ),
        )
    }

    private fun setPane(pane: KLinePane) {
        val panes = store.snapshot.panes.toMutableList()
        val index = panes.indexOfFirst { it.id == pane.id }
        if (index >= 0) panes[index] = pane else panes += pane
        store.setPanes(normalizePaneOrder(panes))
    }

    private fun removePane(paneId: String) {
        store.setPanes(normalizePaneOrder(store.snapshot.panes.filterNot { it.id == paneId }))
    }

    private fun movePane(
        paneId: String,
        destination: Int,
    ) {
        val panes = normalizePaneOrder(store.snapshot.panes).toMutableList()
        val source = panes.indexOfFirst { it.id == paneId }
        if (source < 0) return
        val pane = panes.removeAt(source)
        panes.add(destination.coerceIn(0, panes.size), pane)
        store.setPanes(panes.mapIndexed { index, item -> item.copy(order = index) })
    }

    private fun setPaneState(
        paneId: String,
        state: KLinePaneState,
    ) {
        if (store.snapshot.panes.none { it.id == paneId }) return
        val panes = store.snapshot.panes.map { pane ->
            when {
                pane.id == paneId -> pane.copy(state = state)
                state == KLinePaneState.MAXIMIZED && pane.state == KLinePaneState.MAXIMIZED ->
                    pane.copy(state = KLinePaneState.NORMAL)
                else -> pane
            }
        }
        store.setPanes(normalizePaneOrder(panes))
    }

    private fun normalizePaneOrder(panes: List<KLinePane>): List<KLinePane> = panes
        .sortedWith(compareBy<KLinePane>(KLinePane::order).thenBy(KLinePane::id))
        .mapIndexed { index, pane -> pane.copy(order = index) }

    private fun updateViewport(transform: (KLineViewport, KLineRect) -> KLineViewport) {
        val rect = plotRect ?: return
        val viewport = store.snapshot.viewport ?: return
        store.setViewport(transform(viewport, rect))
    }

    private fun onStoreChanged(
        previous: KLineStoreSnapshot,
        current: KLineStoreSnapshot,
    ) {
        if (previous.bars == current.bars || current.viewportRevision != previous.viewportRevision) return
        val rect = plotRect ?: return
        val viewport = current.viewport
        if (viewport == null) {
            if (current.bars.isNotEmpty()) {
                store.setViewport(
                    KLineViewportEngine.initial(
                        dataCount = current.bars.size,
                        plotWidth = rect.width,
                        config = viewportConfig,
                    ),
                )
            }
            return
        }
        val previousFirst = previous.bars.firstOrNull()?.timestamp
        val insertedBefore = previousFirst?.let { timestamp ->
            current.bars.exactTimestampIndex(timestamp)?.takeIf { it > 0 }
        } ?: 0
        val next = when {
            insertedBefore > 0 -> KLineViewportEngine.preserveAfterPrepend(viewport, insertedBefore)
            viewport.rightOffset >= -1e-9 -> KLineViewportEngine.scrollToLatest(
                viewport = viewport,
                dataCount = current.bars.size,
                plotWidth = rect.width,
                config = viewportConfig,
            )
            else -> viewport.copy(
                rightOffset = viewport.endIndex - (current.bars.size - 1).coerceAtLeast(0),
            )
        }
        store.setViewport(next)
    }
}
