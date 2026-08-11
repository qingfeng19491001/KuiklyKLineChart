package com.tencent.kuiklybase.kline.indicator

class KLineExtensionRegistry(
    templates: Iterable<KLineIndicatorTemplate> = emptyList(),
) {
    private val indicatorTemplates = linkedMapOf<String, KLineIndicatorTemplate>()

    internal var revision: Long = 0
        private set

    init {
        templates.forEach(::register)
    }

    fun register(template: KLineIndicatorTemplate) {
        require(template.name.isNotBlank()) { "Indicator template name must not be blank" }
        indicatorTemplates[template.name] = template
        revision++
    }

    fun unregister(name: String): KLineIndicatorTemplate? = indicatorTemplates.remove(name)?.also {
        revision++
    }

    fun find(name: String): KLineIndicatorTemplate? = indicatorTemplates[name]

    fun templates(): List<KLineIndicatorTemplate> = indicatorTemplates.values.toList()
}
