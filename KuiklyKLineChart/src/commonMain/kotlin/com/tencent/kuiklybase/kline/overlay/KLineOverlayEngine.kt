package com.tencent.kuiklybase.kline.overlay

import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry

internal class KLineOverlayEngine(
    private val registry: KLineExtensionRegistry,
) {
    fun createFigures(instance: KLineOverlayInstance): List<KLineOverlayFigure> {
        val template = requireNotNull(registry.findOverlay(instance.templateName)) {
            "Unknown overlay template: ${instance.templateName}"
        }
        require(instance.points.size >= template.requiredPointCount) {
            "Overlay ${instance.templateName} requires ${template.requiredPointCount} points, " +
                "but received ${instance.points.size}"
        }
        require(
            template.drawingMode == KLineOverlayDrawingMode.CONTINUOUS ||
                instance.points.size == template.requiredPointCount,
        ) {
            "Overlay ${instance.templateName} requires exactly ${template.requiredPointCount} points"
        }
        if (!instance.visible) return emptyList()
        return template.createFigures(KLineOverlayContext(instance)).toList()
    }
}
