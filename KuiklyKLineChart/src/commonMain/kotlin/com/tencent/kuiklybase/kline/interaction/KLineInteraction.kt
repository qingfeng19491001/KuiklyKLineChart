package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.viewport.KLineViewport

internal enum class KLineInteractionState {
    IDLE,
    CROSSHAIR,
    PANNING,
    SCALING,
    DRAWING_OVERLAY,
    DRAGGING_OVERLAY_POINT,
    DRAGGING_OVERLAY,
    RESIZING_PANE,
}

internal enum class KLineViewportGesture { PAN, SCALE }

internal enum class KLineInteractionIntent {
    OVERLAY_CONTROL_POINT,
    OVERLAY_FIGURE,
    CROSSHAIR,
    PANE_SEPARATOR,
    PAN,
    SCALE,
    CLICK,
    NONE,
}

internal data class KLineInteractionCandidates(
    val overlayControlPoint: Boolean = false,
    val overlayFigure: Boolean = false,
    val crosshair: Boolean = false,
    val paneSeparator: Boolean = false,
    val viewportGesture: KLineViewportGesture? = null,
    val ordinaryClick: Boolean = false,
)

internal object KLineInteractionPriorityResolver {
    fun resolve(candidates: KLineInteractionCandidates): KLineInteractionIntent = when {
        candidates.overlayControlPoint -> KLineInteractionIntent.OVERLAY_CONTROL_POINT
        candidates.overlayFigure -> KLineInteractionIntent.OVERLAY_FIGURE
        candidates.crosshair -> KLineInteractionIntent.CROSSHAIR
        candidates.paneSeparator -> KLineInteractionIntent.PANE_SEPARATOR
        candidates.viewportGesture == KLineViewportGesture.PAN -> KLineInteractionIntent.PAN
        candidates.viewportGesture == KLineViewportGesture.SCALE -> KLineInteractionIntent.SCALE
        candidates.ordinaryClick -> KLineInteractionIntent.CLICK
        else -> KLineInteractionIntent.NONE
    }
}

internal data class KLineCrosshair(
    val paneId: String,
    val timestamp: Long,
    val index: Int,
    val value: Double,
) {
    init {
        require(paneId.isNotBlank()) { "Crosshair pane id must not be blank" }
        require(index >= 0) { "Crosshair index must be non-negative" }
        require(value.isFinite()) { "Crosshair value must be finite" }
    }
}

internal data class KLineBarSelection(
    val paneId: String,
    val timestamp: Long,
    val index: Int,
    val value: Double,
) {
    init {
        require(paneId.isNotBlank()) { "Selection pane id must not be blank" }
        require(index >= 0) { "Selection index must be non-negative" }
        require(value.isFinite()) { "Selection value must be finite" }
    }
}

internal sealed interface KLineInteractionSession {
    val state: KLineInteractionState

    data class Crosshair(val position: KLineCrosshair) : KLineInteractionSession {
        override val state = KLineInteractionState.CROSSHAIR
    }

    data class Panning(
        val startPixel: Double,
        val initialViewport: KLineViewport,
    ) : KLineInteractionSession {
        init { require(startPixel.isFinite()) }
        override val state = KLineInteractionState.PANNING
    }

    data class Scaling(
        val focalPixel: Double,
        val initialViewport: KLineViewport,
    ) : KLineInteractionSession {
        init { require(focalPixel.isFinite()) }
        override val state = KLineInteractionState.SCALING
    }

    data class DrawingOverlay(
        val draftId: String,
        val templateName: String,
        val paneId: String,
        val magnetMode: KLineOverlayMagnetMode,
        val points: List<KLineOverlayPoint> = emptyList(),
        val groupId: String? = null,
        val locked: Boolean = false,
    ) : KLineInteractionSession {
        override val state = KLineInteractionState.DRAWING_OVERLAY
    }

    data class DraggingOverlayPoint(
        val instanceId: String,
        val pointIndex: Int,
        val originalPoints: List<KLineOverlayPoint>,
    ) : KLineInteractionSession {
        override val state = KLineInteractionState.DRAGGING_OVERLAY_POINT
    }

    data class DraggingOverlay(
        val instanceId: String,
        val anchor: KLineOverlayPoint,
        val originalPoints: List<KLineOverlayPoint>,
    ) : KLineInteractionSession {
        override val state = KLineInteractionState.DRAGGING_OVERLAY
    }

    data class ResizingPane(
        val separatorIndex: Int,
        val startPixel: Double,
        val initialPanes: List<KLinePane>,
        val initialHeights: List<Double>,
    ) : KLineInteractionSession {
        override val state = KLineInteractionState.RESIZING_PANE
    }
}
