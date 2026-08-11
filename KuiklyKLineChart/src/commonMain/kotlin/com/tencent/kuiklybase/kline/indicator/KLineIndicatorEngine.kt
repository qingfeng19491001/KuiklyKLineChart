package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar
import kotlin.coroutines.cancellation.CancellationException

class KLineIndicatorEngine(
    private val registry: KLineExtensionRegistry,
) {
    private val successCache = mutableMapOf<CacheKey, KLineIndicatorResult>()
    private val failureCache = mutableMapOf<FailureKey, CachedFailure>()

    fun calculate(
        instance: KLineIndicatorInstance,
        bars: List<KLineBar>,
        dataRevision: Long,
    ): KLineIndicatorResult {
        retainActive(listOf(instance), dataRevision)
        val key = cacheKey(instance, dataRevision)
        return when (val outcome = calculateOutcome(instance, bars, key, markFailureReported = false)) {
            is KLineIndicatorCalculation.Success -> outcome.result
            is KLineIndicatorCalculation.Failure -> throw outcome.cause
        }
    }

    internal fun calculateIsolated(
        instance: KLineIndicatorInstance,
        bars: List<KLineBar>,
        dataRevision: Long,
    ): KLineIndicatorCalculation {
        val key = cacheKey(instance, dataRevision)
        return calculateOutcome(instance, bars, key, markFailureReported = true)
    }

    internal fun retainActive(instances: List<KLineIndicatorInstance>, dataRevision: Long) {
        val activeSuccess = instances.mapTo(mutableSetOf()) { cacheKey(it, dataRevision) }
        val activeFailures = instances.mapTo(mutableSetOf()) { FailureKey(it.id, cacheKey(it, dataRevision)) }
        successCache.keys.retainAll(activeSuccess)
        failureCache.keys.retainAll(activeFailures)
    }

    private fun cacheKey(instance: KLineIndicatorInstance, dataRevision: Long) = CacheKey(
        dataRevision = dataRevision,
        registryRevision = registry.indicatorRevision,
        templateName = instance.templateName,
        params = instance.params,
    )

    private fun calculateOutcome(
        instance: KLineIndicatorInstance,
        bars: List<KLineBar>,
        key: CacheKey,
        markFailureReported: Boolean,
    ): KLineIndicatorCalculation {
        successCache[key]?.let { return KLineIndicatorCalculation.Success(it) }
        val failureKey = FailureKey(instance.id, key)
        failureCache[failureKey]?.let { cached ->
            val shouldReport = markFailureReported && !cached.reported
            if (shouldReport) cached.reported = true
            return KLineIndicatorCalculation.Failure(cached.cause, shouldReport)
        }
        val template = registry.find(instance.templateName)
        return try {
            val result = requireNotNull(template) { "Unknown indicator template: ${instance.templateName}" }
                .calculate(bars, instance.params)
            successCache[key] = result
            KLineIndicatorCalculation.Success(result)
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            failureCache[failureKey] = CachedFailure(cause, reported = markFailureReported)
            KLineIndicatorCalculation.Failure(cause, shouldReport = markFailureReported)
        }
    }

    fun clearCache() {
        successCache.clear()
        failureCache.clear()
    }

    private data class CacheKey(
        val dataRevision: Long,
        val registryRevision: Long,
        val templateName: String,
        val params: List<Double>,
    )

    private data class FailureKey(val instanceId: String, val calculation: CacheKey)
    private data class CachedFailure(val cause: Exception, var reported: Boolean)
}

internal sealed interface KLineIndicatorCalculation {
    data class Success(val result: KLineIndicatorResult) : KLineIndicatorCalculation
    data class Failure(
        val cause: Exception,
        val shouldReport: Boolean,
    ) : KLineIndicatorCalculation
}
