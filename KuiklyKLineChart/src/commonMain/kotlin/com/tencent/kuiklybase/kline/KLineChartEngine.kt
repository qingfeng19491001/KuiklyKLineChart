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
import com.tencent.kuiklybase.kline.signal.KLineSignal
import com.tencent.kuiklybase.kline.signal.KLineSignalSet
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class KLineChartEngine(
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

    private val modeFlow = MutableStateFlow(KLineChartMode.FULL)
    private val priceStyleFlow = MutableStateFlow(KLinePriceStyle.CANDLE)
    private val signalsFlow = MutableStateFlow(KLineSignalSet.EMPTY)

    private var currentBounds: KLineRect? = null

    private val errorSubscription = store.observeErrors { error ->
        _errorFlow.value = error
    }
    private val storeSubscription: com.tencent.kuiklybase.kline.store.KLineStoreSubscription
    private val controllerTarget: KLineChartControllerTarget

    init {
        runtime = KLineChartRuntime(store, dataSession, viewportConfig)
        storeSubscription = store.observe { _, current ->
            _snapshotFlow.value = current
        }
        controllerTarget = object : KLineChartControllerTarget {
            override fun execute(command: com.tencent.kuiklybase.kline.controller.KLineControllerCommand) =
                runtime.execute(command)
            override fun exportState() = runtime.exportState()
        }
        controller.attach(controllerTarget)
    }

    fun renderPlanFlow(bounds: KLineRect, overscanBars: Int = 1): Flow<KLineRenderPlan> =
        combine(snapshotFlow, modeFlow, signalsFlow, priceStyleFlow) { snap, mode, signals, priceStyle ->
            planner.create(snap, bounds, overscanBars, mode, priceStyle, signals.toOverlayInstances())
        }

    fun latestRenderPlan(bounds: KLineRect, overscanBars: Int = 1): KLineRenderPlan =
        planner.create(store.snapshot, bounds, overscanBars, modeFlow.value, priceStyleFlow.value, signalsFlow.value.toOverlayInstances())

    fun setMode(mode: KLineChartMode) {
        modeFlow.value = mode
    }

    fun setPriceStyle(style: KLinePriceStyle) {
        priceStyleFlow.value = style
    }

    fun setSignals(signals: List<KLineSignal>) {
        signalsFlow.value = KLineSignalSet(signals)
    }

    fun updateBounds(bounds: KLineRect) {
        currentBounds = bounds
        runtime.updatePlotRect(bounds)
    }

    fun dispatchPointerEvent(event: KLinePointerEvent): KLinePointerDispatchOutcome {
        if (!modeFlow.value.interaction) return KLinePointerDispatchOutcome.Ignored
        val snap = store.snapshot
        val bounds = currentBounds ?: return KLinePointerDispatchOutcome.Ignored
        val viewport = snap.viewport ?: return KLinePointerDispatchOutcome.Ignored
        val bars = snap.bars
        if (bars.isEmpty()) return KLinePointerDispatchOutcome.Ignored
        val xCoord = com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem(bounds, viewport, bars)
        val renderPlan = latestRenderPlan(bounds)
        val hoveredRenderPane = renderPlan.panes.firstOrNull { pane ->
            event.y in pane.rect.top..pane.rect.bottom
        } ?: return KLinePointerDispatchOutcome.Ignored
        val pane = snap.panes.firstOrNull { it.id == hoveredRenderPane.id } ?: return KLinePointerDispatchOutcome.Ignored
        val configuredAxis = pane.yAxes.first()
        val yAxis = configuredAxis.copy(
            minValue = hoveredRenderPane.axis.minValue,
            maxValue = hoveredRenderPane.axis.maxValue,
        )
        val yCoord = com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem(hoveredRenderPane.rect, yAxis)
        if (event is KLinePointerEvent.Tap) {
            val signal = signalsFlow.value.hitTestRendered(renderPlan.overlays, event.x, event.y) ?: signalsFlow.value.hitTest(
                signalsFlow.value.toOverlayInstances(), pane.id, event.x, event.y, xCoord, yCoord,
            )
            if (signal != null) return KLinePointerDispatchOutcome.SignalClick(signal)
            if (pane.kind != com.tencent.kuiklybase.kline.pane.KLinePaneKind.PRICE) {
                return KLinePointerDispatchOutcome.Handled
            }
        }
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
        val paneLayouts = com.tencent.kuiklybase.kline.pane.KLinePaneLayoutEngine.layout(snap.panes, bounds)
        val separatorIndex = paneLayouts.indexOfFirst { layout ->
            layout.visible && layout.separatorHit(event.y, 6.0)
        }.takeIf { it >= 0 && it < paneLayouts.size - 1 }
        val gesture: com.tencent.kuiklybase.kline.interaction.KLineViewportGesture? = when {
            event is KLinePointerEvent.Tap || event is KLinePointerEvent.LongPress -> null
            event is KLinePointerEvent.SecondaryDown -> com.tencent.kuiklybase.kline.interaction.KLineViewportGesture.SCALE
            event.pointerCount >= 2 -> com.tencent.kuiklybase.kline.interaction.KLineViewportGesture.SCALE
            else -> com.tencent.kuiklybase.kline.interaction.KLineViewportGesture.PAN
        }
        val requestCrosshair = event is KLinePointerEvent.LongPress
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
        return KLinePointerDispatchOutcome.Handled
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

sealed interface KLinePointerDispatchOutcome {
    data object Handled : KLinePointerDispatchOutcome
    data object Ignored : KLinePointerDispatchOutcome
    data class SignalClick(val signal: KLineSignal) : KLinePointerDispatchOutcome
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
