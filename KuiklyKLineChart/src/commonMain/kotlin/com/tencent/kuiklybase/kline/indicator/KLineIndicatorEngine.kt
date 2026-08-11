package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

class KLineIndicatorEngine(
    private val registry: KLineExtensionRegistry,
) {
    private val cache = mutableMapOf<CacheKey, KLineIndicatorResult>()

    fun calculate(
        instance: KLineIndicatorInstance,
        bars: List<KLineBar>,
        dataRevision: Long,
    ): KLineIndicatorResult {
        val template = requireNotNull(registry.find(instance.templateName)) {
            "Unknown indicator template: ${instance.templateName}"
        }
        val key = CacheKey(
            dataRevision = dataRevision,
            registryRevision = registry.indicatorRevision,
            templateName = instance.templateName,
            params = instance.params,
        )
        return cache.getOrPut(key) { template.calculate(bars, instance.params) }
    }

    fun clearCache() {
        cache.clear()
    }

    private data class CacheKey(
        val dataRevision: Long,
        val registryRevision: Long,
        val templateName: String,
        val params: List<Double>,
    )
}
