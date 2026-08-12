package com.tencent.kuiklybase.kline

import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.controller.KLineChartControllerTarget
import com.tencent.kuiklybase.kline.controller.KLineChartRuntime
import com.tencent.kuiklybase.kline.data.KLineDataSession
import com.tencent.kuiklybase.kline.data.KLineDataSource
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.render.KLineRenderPlan
import com.tencent.kuiklybase.kline.render.KLineRenderPlanner
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.viewport.KLineViewportConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KLineChartEngine(
    val dataSource: KLineDataSource,
    val controller: KLineChartController = KLineChartController(),
    extensionRegistry: KLineExtensionRegistry = KLineExtensionRegistry.default(),
    private val viewportConfig: KLineViewportConfig = KLineViewportConfig(),
    onError: (KLineError) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = KLineStore(onError = onError, extensionRegistry = extensionRegistry)
    private val dataSession = KLineDataSession(dataSource, store)
    private val runtime: KLineChartRuntime
    private val planner = KLineRenderPlanner(extensionRegistry)

    private val _snapshotFlow = MutableStateFlow(store.snapshot)
    val snapshotFlow: StateFlow<KLineStoreSnapshot> = _snapshotFlow.asStateFlow()

    private val _errorFlow = MutableStateFlow<KLineError?>(null)
    val errorFlow: StateFlow<KLineError?> = _errorFlow.asStateFlow()

    private var currentBounds: KLineRect? = null

    private val errorSubscription = store.observeErrors { error ->
            scope.launch { _errorFlow.emit(error) }
        }
    private val storeSubscription: com.tencent.kuiklybase.kline.store.KLineStoreSubscription
    private val controllerTarget: KLineChartControllerTarget

    init {
        runtime = KLineChartRuntime(store, dataSession, viewportConfig)
        storeSubscription = store.observe { _, current ->
            scope.launch { _snapshotFlow.emit(current) }
        }
        controllerTarget = object : KLineChartControllerTarget {
            override fun execute(command: com.tencent.kuiklybase.kline.controller.KLineControllerCommand) =
                runtime.execute(command)
            override fun exportState() = runtime.exportState()
        }
        controller.attach(controllerTarget)
    }

    fun renderPlanFlow(bounds: KLineRect, overscanBars: Int = 1): Flow<KLineRenderPlan> =
        snapshotFlow.map { snap -> planner.create(snap, bounds, overscanBars) }

    fun latestRenderPlan(bounds: KLineRect, overscanBars: Int = 1): KLineRenderPlan =
        planner.create(snapshotFlow.value, bounds, overscanBars)

    fun updateBounds(bounds: KLineRect) {
        currentBounds = bounds
        runtime.updatePlotRect(bounds)
    }

    fun dispatchPointerEvent(event: KLinePointerEvent) {
        val snap = store.snapshot
        val bounds = currentBounds ?: return
        val viewport = snap.viewport ?: return
        val bars = snap.bars
        if (bars.isEmpty()) return
        val xCoord = com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem(bounds, viewport, bars)
        val paneLayouts = com.tencent.kuiklybase.kline.pane.KLinePaneLayoutEngine.layout(snap.panes, bounds)
        val hoveredLayout = paneLayouts.firstOrNull { layout ->
            layout.visible && event.y in layout.rect.top..layout.rect.bottom
        } ?: return
        val pane = snap.panes.firstOrNull { it.id == hoveredLayout.paneId } ?: return
        val yAxis = pane.yAxes.first()
        val yCoord = com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem(hoveredLayout.rect, yAxis)
        var overlayHit: com.tencent.kuiklybase.kline.interaction.KLineOverlayHit? = null
        for (instance in snap.overlayInstances) {
            val hit = com.tencent.kuiklybase.kline.interaction.KLineOverlayHitTester.hitTest(
                instance = instance,
                pixelX = event.x,
                pixelY = event.y,
                xCoordinates = xCoord,
                yCoordinates = yCoord,
                controlPointRadius = 8.0,
            )
            if (hit != null) { overlayHit = hit; break }
        }
        val separatorIndex = paneLayouts.indexOfFirst { layout ->
            layout.visible && layout.separatorHit(event.y, 6.0)
        }.takeIf { it >= 0 && it < paneLayouts.size - 1 }
        val gesture: com.tencent.kuiklybase.kline.interaction.KLineViewportGesture? = when {
            event is KLinePointerEvent.SecondaryDown -> com.tencent.kuiklybase.kline.interaction.KLineViewportGesture.SCALE
            event.pointerCount >= 2 -> com.tencent.kuiklybase.kline.interaction.KLineViewportGesture.SCALE
            else -> com.tencent.kuiklybase.kline.interaction.KLineViewportGesture.PAN
        }
        val requestCrosshair = event is KLinePointerEvent.LongPress || event is KLinePointerEvent.SecondaryDown
        val ordinaryClick = event is KLinePointerEvent.Tap
        val request = com.tencent.kuiklybase.kline.interaction.KLinePointerDownRequest(
            paneId = pane.id,
            pixelX = event.x,
            pixelY = event.y,
            xCoordinates = xCoord,
            yCoordinates = yCoord,
            overlayHit = overlayHit,
            requestCrosshair = requestCrosshair,
            separatorIndex = separatorIndex,
            paneLayouts = paneLayouts,
            viewportGesture = gesture,
            ordinaryClick = ordinaryClick,
        )
        val engine = runtime.interactionEngine
        when (event) {
            is KLinePointerEvent.Down, is KLinePointerEvent.SecondaryDown, is KLinePointerEvent.LongPress, is KLinePointerEvent.Tap -> {
                engine.handlePointerDown(request)
            }
            is KLinePointerEvent.Move -> {
                when (val session = snap.interactionSession) {
                    is com.tencent.kuiklybase.kline.interaction.KLineInteractionSession.Crosshair -> {
                        engine.showCrosshair(pane.id, event.x, event.y, xCoord, yCoord)
                    }
                    is com.tencent.kuiklybase.kline.interaction.KLineInteractionSession.Panning -> {
                        engine.updatePan(event.x, bounds)
                    }
                    is com.tencent.kuiklybase.kline.interaction.KLineInteractionSession.Scaling -> {
                        engine.updateScale(event.scaleFactor, bounds)
                    }
                    is com.tencent.kuiklybase.kline.interaction.KLineInteractionSession.ResizingPane -> {
                        engine.paneResize.updateResize(event.y)
                    }
                    is com.tencent.kuiklybase.kline.interaction.KLineInteractionSession.DraggingOverlayPoint -> {
                        engine.overlay.dragPointTo(event.x, event.y, xCoord, yCoord)
                    }
                    is com.tencent.kuiklybase.kline.interaction.KLineInteractionSession.DraggingOverlay -> {
                        engine.overlay.dragOverlayTo(event.x, event.y, xCoord, yCoord)
                    }
                    is com.tencent.kuiklybase.kline.interaction.KLineInteractionSession.DrawingOverlay -> {
                        engine.overlay.updateDraftPoint(event.x, event.y, xCoord, yCoord, pane.id)
                    }
                    else -> {}
                }
            }
            is KLinePointerEvent.Up -> {
                if (snap.interactionState == com.tencent.kuiklybase.kline.interaction.KLineInteractionState.DRAWING_OVERLAY) {
                    engine.overlay.commitDraftIfReady()
                }
                engine.endInteraction()
            }
            is KLinePointerEvent.Cancel -> {
                engine.cancelInteraction()
            }
        }
    }

    fun triggerLoadBefore() { dataSession.loadBefore() }
    fun triggerLoadAfter() { dataSession.loadAfter() }
    fun retryInitialLoad() { dataSession.retryInitial() }

    fun dispose() {
        controller.detach(controllerTarget)
        storeSubscription.cancel()
        errorSubscription.cancel()
        runtime.dispose()
        scope.cancel()
    }
}

sealed interface KLinePointerEvent {
    val x: Double
    val y: Double
    val pointerCount: Int get() = 1

    data class Down(override val x: Double, override val y: Double) : KLinePointerEvent
    data class SecondaryDown(override val x: Double, override val y: Double) : KLinePointerEvent
    data class LongPress(override val x: Double, override val y: Double) : KLinePointerEvent
    data class Tap(override val x: Double, override val y: Double) : KLinePointerEvent
    data class Move(
        override val x: Double,
        override val y: Double,
        val scaleFactor: Double = 1.0,
        override val pointerCount: Int = 1,
    ) : KLinePointerEvent
    data class Up(override val x: Double, override val y: Double) : KLinePointerEvent
    data class Cancel(override val x: Double, override val y: Double) : KLinePointerEvent
}
