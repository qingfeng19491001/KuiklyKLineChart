package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.overlay.KLineOverlayDrawingMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayEngine
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem

internal class KLineOverlayInteractionEngine(
    private val store: KLineStore,
    private val registry: KLineExtensionRegistry,
) {
    private val overlayEngine = KLineOverlayEngine(registry)
    private var nextDraftId = 1L

    fun beginOverlay(
        templateName: String,
        paneId: String = "price",
        magnetMode: KLineOverlayMagnetMode = KLineOverlayMagnetMode.NONE,
        draftId: String = "overlay-draft-${nextDraftId++}",
    ): String {
        requireNotNull(registry.findOverlay(templateName)) { "Unknown overlay template: $templateName" }
        require(draftId.isNotBlank()) { "Overlay draft id must not be blank" }
        require(store.snapshot.overlayInstances.none { it.id == draftId }) { "Overlay instance id already exists: $draftId" }
        store.beginInteraction(
            KLineInteractionSession.DrawingOverlay(draftId, templateName, paneId, magnetMode),
        )
        return draftId
    }

    fun addOverlayPoint(
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Boolean {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.DrawingOverlay ?: return false
        val point = KLineMagnetResolver.resolve(
            pixelX, pixelY, session.magnetMode, store.snapshot.bars, xCoordinates, yCoordinates,
        ) ?: return false
        val template = requireNotNull(registry.findOverlay(session.templateName))
        val next = session.copy(points = session.points + point)
        if (template.drawingMode == KLineOverlayDrawingMode.POINT_BY_POINT && next.points.size == template.requiredPointCount) {
            return completeDraft(next)
        } else {
            store.updateInteraction(next)
        }
        return true
    }

    fun finishOverlay(): Boolean {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.DrawingOverlay ?: return false
        val template = requireNotNull(registry.findOverlay(session.templateName))
        if (session.points.size < template.requiredPointCount) {
            store.cancelInteraction()
            return false
        }
        return completeDraft(session)
    }

    fun beginDragPoint(instanceId: String, pointIndex: Int): Boolean {
        val instance = store.snapshot.overlayInstances.find { it.id == instanceId } ?: return false
        if (instance.locked || pointIndex !in instance.points.indices || store.snapshot.interactionState != KLineInteractionState.IDLE) return false
        store.selectOverlay(instanceId)
        store.beginInteraction(KLineInteractionSession.DraggingOverlayPoint(instanceId, pointIndex, instance.points))
        return true
    }

    fun updateDragPoint(
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Boolean {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.DraggingOverlayPoint ?: return false
        val instance = store.snapshot.overlayInstances.find { it.id == session.instanceId } ?: return false
        if (instance.locked) return false
        val point = KLineMagnetResolver.resolve(
            pixelX, pixelY, instance.magnetMode, store.snapshot.bars, xCoordinates, yCoordinates,
        ) ?: return false
        val points = instance.points.toMutableList()
        points[session.pointIndex] = point
        store.updateOverlay(instance.copy(points = points))
        return true
    }

    fun beginDragOverlayAtPixel(
        instanceId: String,
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Boolean {
        val instance = store.snapshot.overlayInstances.find { it.id == instanceId } ?: return false
        if (instance.locked || store.snapshot.interactionState != KLineInteractionState.IDLE) return false
        val anchor = KLineMagnetResolver.resolve(
            pixelX, pixelY, instance.magnetMode, store.snapshot.bars, xCoordinates, yCoordinates,
        ) ?: return false
        store.selectOverlay(instanceId)
        store.beginInteraction(KLineInteractionSession.DraggingOverlay(instanceId, anchor, instance.points))
        return true
    }

    fun updateDragOverlay(
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Boolean {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.DraggingOverlay ?: return false
        val instance = store.snapshot.overlayInstances.find { it.id == session.instanceId } ?: return false
        if (instance.locked) return false
        val current = KLineMagnetResolver.resolve(
            pixelX, pixelY, instance.magnetMode, store.snapshot.bars, xCoordinates, yCoordinates,
        ) ?: return false
        val timestampDelta = subtractExact(current.timestamp, session.anchor.timestamp) ?: return false
        val valueDelta = current.value - session.anchor.value
        if (!valueDelta.isFinite()) return false
        val translated = session.originalPoints.map { point ->
            val timestamp = addExact(point.timestamp, timestampDelta) ?: return false
            val value = point.value + valueDelta
            if (!value.isFinite()) return false
            KLineOverlayPoint(timestamp, value)
        }
        store.updateOverlay(instance.copy(points = translated))
        return true
    }

    fun dragPointTo(
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Boolean = updateDragPoint(pixelX, pixelY, xCoordinates, yCoordinates)

    fun dragOverlayTo(
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Boolean = updateDragOverlay(pixelX, pixelY, xCoordinates, yCoordinates)

    fun updateDraftPoint(
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
        paneId: String,
    ): Boolean {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.DrawingOverlay ?: return false
        val point = KLineMagnetResolver.resolve(
            pixelX, pixelY, session.magnetMode, store.snapshot.bars, xCoordinates, yCoordinates,
        ) ?: return false
        val template = requireNotNull(registry.findOverlay(session.templateName))
        val updated = session.points.toMutableList()
        if (updated.isEmpty()) {
            updated.add(point)
        } else {
            updated[updated.lastIndex] = point
        }
        if (template.drawingMode == KLineOverlayDrawingMode.CONTINUOUS && updated.size >= template.requiredPointCount) {
            // Append continuously
        }
        store.updateInteraction(session.copy(points = updated, paneId = paneId))
        return true
    }

    fun commitDraftIfReady(): Boolean {
        val session = store.snapshot.interactionSession as? KLineInteractionSession.DrawingOverlay ?: return false
        val template = requireNotNull(registry.findOverlay(session.templateName))
        when {
            session.points.size < template.requiredPointCount -> {
                store.cancelInteraction()
                return false
            }
            else -> return completeDraft(session)
        }
    }

    fun endInteraction() = store.finishInteraction()

    fun cancelInteraction() {
        when (val session = store.snapshot.interactionSession) {
            is KLineInteractionSession.DraggingOverlay -> restore(session.instanceId, session.originalPoints)
            is KLineInteractionSession.DraggingOverlayPoint -> restore(session.instanceId, session.originalPoints)
            else -> Unit
        }
        store.cancelInteraction()
    }

    fun deleteSelectedOverlay(): Boolean {
        val id = store.snapshot.selectedOverlayId ?: return false
        store.removeOverlay(id)
        return true
    }

    private fun completeDraft(session: KLineInteractionSession.DrawingOverlay): Boolean {
        val instance = KLineOverlayInstance(
            id = session.draftId,
            templateName = session.templateName,
            paneId = session.paneId,
            points = session.points,
            magnetMode = session.magnetMode,
        )
        try {
            overlayEngine.createFigures(instance)
        } catch (_: IllegalArgumentException) {
            return false
        }
        store.addOverlay(instance)
        store.selectOverlay(instance.id)
        store.finishInteraction()
        return true
    }

    private fun restore(instanceId: String, points: List<KLineOverlayPoint>) {
        store.snapshot.overlayInstances.find { it.id == instanceId }?.let { store.updateOverlay(it.copy(points = points)) }
    }

    private fun addExact(left: Long, right: Long): Long? {
        if (right > 0 && left > Long.MAX_VALUE - right) return null
        if (right < 0 && left < Long.MIN_VALUE - right) return null
        return left + right
    }

    private fun subtractExact(left: Long, right: Long): Long? {
        if (right > 0 && left < Long.MIN_VALUE + right) return null
        if (right < 0 && left > Long.MAX_VALUE + right) return null
        return left - right
    }
}
