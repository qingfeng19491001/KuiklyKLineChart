package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar
import kotlin.coroutines.cancellation.CancellationException

internal class KLineIndicatorEngine(
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
        updateContext: KLineIndicatorUpdateContext? = null,
    ): KLineIndicatorCalculation {
        val key = cacheKey(instance, dataRevision)
        return calculateOutcome(instance, bars, key, markFailureReported = true, updateContext = updateContext)
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
        updateContext: KLineIndicatorUpdateContext? = null,
    ): KLineIndicatorCalculation {
        val cachedTemplate = registry.find(instance.templateName)
        successCache[key]?.let { cached ->
            val previous = updateContext?.previousResult
            val visiblyEqual = previous != null && incrementalEquivalent(previous, cached, updateContext, cachedTemplate)
            val result = if (visiblyEqual && previous!!.hasEquivalentCalculationState(cached)) previous else cached
            return KLineIndicatorCalculation.Success(result, !visiblyEqual)
        }
        val failureKey = FailureKey(instance.id, key)
        failureCache[failureKey]?.let { cached ->
            val shouldReport = markFailureReported && !cached.reported
            if (shouldReport) cached.reported = true
            return KLineIndicatorCalculation.Failure(cached.cause, shouldReport)
        }
        val template = registry.find(instance.templateName)
        return try {
            val resolved = requireNotNull(template) { "Unknown indicator template: ${instance.templateName}" }
            var usedIncremental = false
            val result = if (updateContext != null && updateContext.change.kind != KLineDataChangeKind.FULL_REPLACE) {
                try {
                    resolved.calculateIncremental(updateContext, instance.params)?.also {
                        validateResult(resolved, instance.params, bars.size, it, updateContext)
                        usedIncremental = true
                    } ?: resolved.calculate(bars, instance.params).also {
                        recordFullFallback(updateContext)
                        validateResult(resolved, instance.params, bars.size, it, updateContext)
                    }
                } catch (cause: Exception) {
                    if (cause is CancellationException) throw cause
                    recordFullFallback(updateContext)
                    resolved.calculate(bars, instance.params).also {
                        validateResult(resolved, instance.params, bars.size, it, updateContext)
                    }
                }
            } else {
                resolved.calculate(bars, instance.params).also {
                    validateResult(resolved, instance.params, bars.size, it, updateContext)
                }
            }
            val previous = updateContext?.previousResult
            val visiblyEqual = when {
                previous == null -> false
                usedIncremental -> incrementalEquivalent(previous, result, updateContext, resolved)
                else -> previous == result
            }
            val published = when {
                previous == null -> result
                visiblyEqual && previous.hasEquivalentCalculationState(result) -> previous
                else -> result
            }
            successCache[key] = published
            KLineIndicatorCalculation.Success(published, !visiblyEqual)
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            failureCache[failureKey] = CachedFailure(cause, reported = markFailureReported)
            KLineIndicatorCalculation.Failure(cause, shouldReport = markFailureReported)
        }
    }

    private fun validateResult(
        template: KLineIndicatorTemplate,
        params: List<Double>,
        barCount: Int,
        result: KLineIndicatorResult,
        context: KLineIndicatorUpdateContext?,
    ) {
        val schema = template.figureSchema(params)
        val tracker = context?.performanceTracker
        val performanceKey = context?.performanceKey
        if (tracker != null && performanceKey != null) tracker.recordSchemaChecks(performanceKey, schema.size + result.figures.size)
        require(schema.map(KLineIndicatorFigure::key).distinct().size == schema.size) { "Indicator schema figure keys must be unique" }
        require(result.templateName == template.name && result.series == template.series) { "Indicator result identity mismatch" }
        require(result.figures.size == schema.size) { "Indicator result figure count mismatch" }
        require(result.figures.map(KLineIndicatorFigureResult::key).distinct().size == result.figures.size) { "Indicator result figure keys must be unique" }
        result.figures.zip(schema).forEach { (actual, expected) ->
            require(actual.key == expected.key && actual.type == expected.type) { "Indicator result figure mismatch: ${actual.key}" }
            require(actual.values.size == barCount) { "Indicator result length must match bars" }
        }
    }

    private fun recordFullFallback(context: KLineIndicatorUpdateContext) {
        val tracker = context.performanceTracker ?: return
        val key = context.performanceKey ?: return
        tracker.recordFullFallback(key)
        tracker.recordFullCalculation(key)
    }

    private fun incrementalEquivalent(
        previous: KLineIndicatorResult,
        current: KLineIndicatorResult,
        context: KLineIndicatorUpdateContext,
        template: KLineIndicatorTemplate?,
    ): Boolean {
        if (previous.templateName != current.templateName || previous.series != current.series ||
            previous.figures.size != current.figures.size
        ) return false
        if (previous.figures.indices.any { index ->
            val oldFigure = previous.figures[index]
            val newFigure = current.figures[index]
            oldFigure.key != newFigure.key || oldFigure.type != newFigure.type
        }) return false
        if (context.oldBars.size != context.newBars.size) return false
        val range = try {
            template?.outputAffectedRange(context)
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            null
        } ?: return previous == current
        if (range.isEmpty() || range.first < 0 || range.last >= context.newBars.size) return previous == current
        return previous.figures.indices.all { figureIndex ->
            val oldValues = previous.figures[figureIndex].values
            val newValues = current.figures[figureIndex].values
            oldValues.size == newValues.size && range.all { index -> oldValues[index] == newValues[index] }
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
    data class Success(val result: KLineIndicatorResult, val changed: Boolean = true) : KLineIndicatorCalculation
    data class Failure(
        val cause: Exception,
        val shouldReport: Boolean,
    ) : KLineIndicatorCalculation
}
