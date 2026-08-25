package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.pane.KLinePaneLayout
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.viewport.KLineViewportConfig
import com.tencent.kuiklybase.kline.viewport.KLineViewportEngine
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem

internal data class KLinePointerDownRequest(
    val paneId: String,
    val pixelX: Double,
    val pixelY: Double,
    val xCoordinates: KLineXCoordinateSystem,
    val yCoordinates: KLineYCoordinateSystem,
    val overlayHit: KLineOverlayHit? = null,
    val requestCrosshair: Boolean = false,
    val separatorIndex: Int? = null,
    val paneLayouts: List<KLinePaneLayout> = emptyList(),
    val viewportGesture: KLineViewportGesture? = null,
    val ordinaryClick: Boolean = true,
)

internal class KLineInteractionEngine(
    private val store: KLineStore,
    registry: KLineExtensionRegistry,
    private val viewportConfig: KLineViewportConfig = KLineViewportConfig(),
) {
    val overlay = KLineOverlayInteractionEngine(store, registry)
    val paneResize = KLinePaneResizeEngine(store)

    fun handlePointerDown(request: KLinePointerDownRequest): KLineInteractionIntent {
        val actionableOverlayHit = request.overlayHit?.takeIf { hit ->
            store.snapshot.overlayInstances.find { it.id == hit.instanceId }?.locked == false
        }
        val intent = KLineInteractionPriorityResolver.resolve(
            KLineInteractionCandidates(
                overlayControlPoint = actionableOverlayHit?.type == KLineOverlayHitType.CONTROL_POINT,
                overlayFigure = actionableOverlayHit?.type == KLineOverlayHitType.FIGURE,
                crosshair = request.requestCrosshair,
                paneSeparator = request.separatorIndex != null,
                viewportGesture = request.viewportGesture,
                ordinaryClick = request.ordinaryClick,
            ),
        )
        val started = when (intent) {
            KLineInteractionIntent.OVERLAY_CONTROL_POINT -> actionableOverlayHit?.pointIndex?.let { index ->
                overlay.beginDragPoint(actionableOverlayHit.instanceId, index)
            } == true
            KLineInteractionIntent.OVERLAY_FIGURE -> {
                actionableOverlayHit != null && overlay.beginDragOverlayAtPixel(
                    actionableOverlayHit.instanceId,
                    request.pixelX,
                    request.pixelY,
                    request.xCoordinates,
                    request.yCoordinates,
                )
            }
            KLineInteractionIntent.CROSSHAIR -> {
                // Hosts report a pointer down as soon as the finger lands, which already started a
                // viewport gesture for this very touch. A long press only arrives afterwards, so roll
                // that gesture back before the crosshair takes over.
                if (store.snapshot.interactionState in PREEMPTABLE_BY_CROSSHAIR) {
                    cancelInteraction()
                }
                showCrosshair(
                    request.paneId, request.pixelX, request.pixelY, request.xCoordinates, request.yCoordinates,
                )
            }
            KLineInteractionIntent.PANE_SEPARATOR -> request.separatorIndex?.let { index ->
                paneResize.beginResize(index, request.pixelY, request.paneLayouts)
            } == true
            KLineInteractionIntent.PAN -> beginPan(request.pixelX)
            KLineInteractionIntent.SCALE -> beginScale(request.pixelX)
            KLineInteractionIntent.CLICK -> click(
                request.paneId, request.pixelX, request.pixelY, request.xCoordinates, request.yCoordinates,
            )
            KLineInteractionIntent.NONE -> true
        }
        return if (started) intent else KLineInteractionIntent.NONE
    }

    fun showCrosshair(
        paneId: String,
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Boolean {
        val selection = marketSelection(paneId, pixelX, pixelY, xCoordinates, yCoordinates) ?: return false
        val crosshair = KLineCrosshair(selection.paneId, selection.timestamp, selection.index, selection.value)
        when (store.snapshot.interactionState) {
            KLineInteractionState.IDLE -> store.beginInteraction(KLineInteractionSession.Crosshair(crosshair))
            KLineInteractionState.CROSSHAIR -> store.updateInteraction(KLineInteractionSession.Crosshair(crosshair))
            else -> return false
        }
        return true
    }

    fun clearCrosshair() = store.clearCrosshair()

    fun click(
        paneId: String,
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Boolean {
        val selection = marketSelection(paneId, pixelX, pixelY, xCoordinates, yCoordinates) ?: return false
        store.setClickSelection(
            if (store.snapshot.clickSelection?.index == selection.index) null else selection,
        )
        return true
    }

    fun beginPan(startPixel: Double): Boolean {
        val viewport = store.snapshot.viewport ?: return false
        if (store.snapshot.interactionState != KLineInteractionState.IDLE || !startPixel.isFinite()) return false
        store.beginInteraction(KLineInteractionSession.Panning(startPixel, viewport))
        return true
    }

    fun updatePan(pixel: Double, plot: KLineRect): Boolean {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.Panning ?: return false
        val initial = session.initialViewport
        if (!pixel.isFinite()) return false
        store.setViewport(
            KLineViewportEngine.panByPixels(
                initial, pixel - session.startPixel, store.snapshot.bars.size, plot.width, viewportConfig,
            ),
        )
        return true
    }

    fun beginScale(focalPixel: Double): Boolean {
        val viewport = store.snapshot.viewport ?: return false
        if (store.snapshot.interactionState != KLineInteractionState.IDLE || !focalPixel.isFinite()) return false
        store.beginInteraction(KLineInteractionSession.Scaling(focalPixel, viewport))
        return true
    }

    fun updateScale(factor: Double, plot: KLineRect): Boolean {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.Scaling ?: return false
        val initial = session.initialViewport
        if (!factor.isFinite() || factor <= 0.0) return false
        store.setViewport(
            KLineViewportEngine.zoomAtPixel(
                initial, factor, session.focalPixel, plot.left, plot.width, store.snapshot.bars.size, viewportConfig,
            ),
        )
        return true
    }

    fun endInteraction() = store.finishInteraction()

    fun cancelInteraction() {
        when (val session = store.snapshot.interactionSession) {
            is KLineInteractionSession.Panning -> store.setViewport(session.initialViewport)
            is KLineInteractionSession.Scaling -> store.setViewport(session.initialViewport)
            is KLineInteractionSession.DraggingOverlay,
            is KLineInteractionSession.DraggingOverlayPoint,
            is KLineInteractionSession.DrawingOverlay -> {
                overlay.cancelInteraction()
                return
            }
            is KLineInteractionSession.ResizingPane -> {
                paneResize.cancelResize()
                return
            }
            else -> Unit
        }
        store.cancelInteraction()
    }

    private fun marketSelection(
        paneId: String,
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): KLineBarSelection? {
        if (!pixelX.isFinite() || !pixelY.isFinite() || paneId.isBlank()) return null
        val index = kotlin.math.round(xCoordinates.pixelToIndex(pixelX)).toInt()
        val bar = store.snapshot.bars.getOrNull(index) ?: return null
        if (kotlin.math.abs(xCoordinates.indexToPixel(index.toDouble()) - pixelX) > CLICK_HIT_RADIUS) return null
        val value = yCoordinates.pixelToValue(pixelY)
        if (!value.isFinite()) return null
        return KLineBarSelection(paneId, bar.timestamp, index, value)
    }

    private companion object {
        const val CLICK_HIT_RADIUS = 24.0
        val PREEMPTABLE_BY_CROSSHAIR = setOf(
            KLineInteractionState.PANNING,
            KLineInteractionState.SCALING,
        )
    }
}
