package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.overlay.KLineOverlayTemplate

class KLineExtensionRegistry(
    templates: Iterable<KLineIndicatorTemplate> = emptyList(),
    overlayTemplates: Iterable<KLineOverlayTemplate> = emptyList(),
) {
    private val indicatorTemplates = linkedMapOf<String, KLineIndicatorTemplate>()
    private val overlays = linkedMapOf<String, KLineOverlayTemplate>()

    internal var indicatorRevision: Long = 0
        private set
    internal var overlayRevision: Long = 0
        private set

    init {
        templates.forEach(::register)
        overlayTemplates.forEach(::registerOverlay)
    }

    fun register(template: KLineIndicatorTemplate) {
        require(template.name.isNotBlank()) { "Indicator template name must not be blank" }
        indicatorTemplates[template.name] = template
        indicatorRevision++
    }

    fun unregister(name: String): KLineIndicatorTemplate? = indicatorTemplates.remove(name)?.also {
        indicatorRevision++
    }

    fun find(name: String): KLineIndicatorTemplate? = indicatorTemplates[name]

    fun templates(): List<KLineIndicatorTemplate> = indicatorTemplates.values.toList()

    fun registerOverlay(template: KLineOverlayTemplate) {
        require(template.name.isNotBlank()) { "Overlay template name must not be blank" }
        require(template.requiredPointCount > 0) { "Overlay required point count must be positive" }
        overlays[template.name] = template
        overlayRevision++
    }

    fun unregisterOverlay(name: String): KLineOverlayTemplate? = overlays.remove(name)?.also {
        overlayRevision++
    }

    fun findOverlay(name: String): KLineOverlayTemplate? = overlays[name]

    fun overlayTemplates(): List<KLineOverlayTemplate> = overlays.values.toList()
}
