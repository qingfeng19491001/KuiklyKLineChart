package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

object KLineMacdIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "MACD"
    override val defaultParams = listOf(12.0, 26.0, 9.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("DIFF", "DIFF", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("DEA", "DEA", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MACD", "MACD", KLineIndicatorFigureType.BAR),
    )

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 3) { "MACD expects fast, slow and signal periods" }
        val close = bars.map(KLineBar::close)
        val fast = exponentialMovingAverage(close, positivePeriod(resolved[0]))
        val slow = exponentialMovingAverage(close, positivePeriod(resolved[1]))
        val diff = combine(fast, slow) { left, right -> left - right }
        val dea = exponentialMovingAverageNullable(diff, positivePeriod(resolved[2]))
        return KLineIndicatorResult(
            name, series,
            listOf(
                lineFigure("DIFF", diff),
                lineFigure("DEA", dea),
                barFigure("MACD", combine(diff, dea) { value, signal -> (value - signal) * 2.0 }),
            ),
            MacdTailState(
                fast.getOrNull(fast.lastIndex - 1), slow.getOrNull(slow.lastIndex - 1), dea.getOrNull(dea.lastIndex - 1),
                fast.lastOrNull(), slow.lastOrNull(), dea.lastOrNull(),
            ),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val resolved = params.ifEmpty { defaultParams }
        val fastAlpha = 2.0 / (positivePeriod(resolved[0]) + 1.0)
        val slowAlpha = 2.0 / (positivePeriod(resolved[1]) + 1.0)
        val signalAlpha = 2.0 / (positivePeriod(resolved[2]) + 1.0)
        val start = context.change.newAffectedRange.first
        val oldState = context.previousResult.calculationState as? MacdTailState ?: return calculate(context.newBars, params)
        val replacingTail = context.change.kind == KLineDataChangeKind.TAIL_UPDATE
        var fast = if (replacingTail) oldState.beforeLastFast else oldState.lastFast
        var slow = if (replacingTail) oldState.beforeLastSlow else oldState.lastSlow
        var signal = if (replacingTail) oldState.beforeLastDea else oldState.lastDea
        var beforeFast = fast
        var beforeSlow = slow
        var beforeSignal = signal
        val changedDiff = mutableListOf<Double?>()
        val changedSignal = mutableListOf<Double?>()
        context.newBars.subList(start, context.newBars.size).forEach { bar ->
            beforeFast = fast
            beforeSlow = slow
            beforeSignal = signal
            fast = fast?.let { fastAlpha * bar.close + (1.0 - fastAlpha) * it } ?: bar.close
            slow = slow?.let { slowAlpha * bar.close + (1.0 - slowAlpha) * it } ?: bar.close
            val diff = finiteOrNull(fast!! - slow!!)
            signal = diff?.let { value -> finiteOrNull(signal?.let { signalValue -> signalAlpha * value + (1.0 - signalAlpha) * signalValue } ?: value) }
            changedDiff += diff
            changedSignal += signal
        }
        val oldDiff = context.previousResult.figures[0].values
        val diff = oldDiff.persistentReplaceRange(start, context.oldBars.size, changedDiff, context)
        val dea = context.previousResult.figures[1].values.persistentReplaceRange(start, context.oldBars.size, changedSignal, context)
        val changedMacd = changedDiff.zip(changedSignal).map { (value, valueSignal) ->
            if (value == null || valueSignal == null) null else finiteOrNull((value - valueSignal) * 2.0)
        }
        val macd = context.previousResult.figures[2].values.persistentReplaceRange(start, context.oldBars.size, changedMacd, context)
        return KLineIndicatorResult(
            name, series,
            listOf(lineFigure("DIFF", diff), lineFigure("DEA", dea), barFigure("MACD", macd)),
            MacdTailState(beforeFast, beforeSlow, beforeSignal, fast, slow, signal),
        )
    }
}

private data class MacdTailState(
    val beforeLastFast: Double?,
    val beforeLastSlow: Double?,
    val beforeLastDea: Double?,
    val lastFast: Double?,
    val lastSlow: Double?,
    val lastDea: Double?,
)

object KLineKdjIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "KDJ"
    override val defaultParams = listOf(9.0, 3.0, 3.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf("K", "D", "J").map { key ->
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 3) { "KDJ expects lookback, K and D periods" }
        val period = positivePeriod(resolved[0])
        val rsv = bars.indices.map { index ->
            if (index + 1 < period) null else {
                val window = bars.subList(index - period + 1, index + 1)
                val low = window.minOf(KLineBar::low)
                val high = window.maxOf(KLineBar::high)
                if (high == low) 50.0 else finiteOrNull((bars[index].close - low) / (high - low) * 100.0)
            }
        }
        val k = chineseSmoothed(rsv, positivePeriod(resolved[1]), 50.0)
        val d = chineseSmoothed(k, positivePeriod(resolved[2]), 50.0)
        return indicatorResult(
            name,
            series,
            lineFigure("K", k),
            lineFigure("D", d),
            lineFigure("J", combine(k, d) { kv, dv -> 3.0 * kv - 2.0 * dv }),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val resolved = params.ifEmpty { defaultParams }
        val lookback = positivePeriod(resolved[0])
        val kPeriod = positivePeriod(resolved[1])
        val dPeriod = positivePeriod(resolved[2])
        val start = context.change.newAffectedRange.first
        var previousK = context.previousResult.figures[0].values.getOrNull(start - 1) ?: 50.0
        var previousD = context.previousResult.figures[1].values.getOrNull(start - 1) ?: 50.0
        val changedK = mutableListOf<Double?>()
        val changedD = mutableListOf<Double?>()
        (start until context.newBars.size).forEach { index ->
            val rsv = if (index + 1 < lookback) null else {
                val window = context.newBars.subList(index - lookback + 1, index + 1)
                val low = window.minOf(KLineBar::low)
                val high = window.maxOf(KLineBar::high)
                if (high == low) 50.0 else finiteOrNull((context.newBars[index].close - low) / (high - low) * 100.0)
            }
            val k = rsv?.let { finiteOrNull((previousK * (kPeriod - 1) + it) / kPeriod) }
            if (k != null) previousK = k
            val d = k?.let { finiteOrNull((previousD * (dPeriod - 1) + it) / dPeriod) }
            if (d != null) previousD = d
            changedK += k
            changedD += d
        }
        val k = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changedK, context)
        val d = context.previousResult.figures[1].values.persistentReplaceRange(start, context.oldBars.size, changedD, context)
        val changedJ = changedK.zip(changedD).map { (kv, dv) ->
            if (kv == null || dv == null) null else finiteOrNull(3.0 * kv - 2.0 * dv)
        }
        val j = context.previousResult.figures[2].values.persistentReplaceRange(start, context.oldBars.size, changedJ, context)
        return indicatorResult(name, series, lineFigure("K", k), lineFigure("D", d), lineFigure("J", j))
    }
}

object KLineRsiIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "RSI"
    override val defaultParams = listOf(6.0, 12.0, 24.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = defaultParams.map { period ->
        val key = "RSI${period.toInt()}"
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }
    override fun figureSchema(params: List<Double>) = params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
        KLineIndicatorFigure("RSI$period", "RSI$period", KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val calculated = params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
            relativeStrengthIndexWithState(bars.map(KLineBar::close), period)
        }
        return KLineIndicatorResult(
            name, series,
            calculated.map { (values, state) -> lineFigure("RSI${state.period}", values) },
            calculated.map { it.second },
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val states = context.previousResult.calculationState as? List<*> ?: return calculate(context.newBars, params)
        val start = context.change.newAffectedRange.first
        val calculated = params.ifEmpty { defaultParams }.map(::positivePeriod).mapIndexed { figureIndex, period ->
            val oldState = states.getOrNull(figureIndex) as? RsiTailState ?: return calculate(context.newBars, params)
            var averageGain = if (context.change.kind == KLineDataChangeKind.TAIL_APPEND) oldState.lastGain else oldState.beforeLastGain
            var averageLoss = if (context.change.kind == KLineDataChangeKind.TAIL_APPEND) oldState.lastLoss else oldState.beforeLastLoss
            var beforeLastGain = averageGain
            var beforeLastLoss = averageLoss
            val changed = (start until context.newBars.size).map { index ->
                beforeLastGain = averageGain
                beforeLastLoss = averageLoss
                if (index < period) null else {
                    val change = context.newBars[index].close - context.newBars[index - 1].close
                    if (averageGain == null || averageLoss == null) {
                        val changes = (index - period + 1..index).map { context.newBars[it].close - context.newBars[it - 1].close }
                        averageGain = changes.sumOf { maxOf(it, 0.0) } / period
                        averageLoss = changes.sumOf { maxOf(-it, 0.0) } / period
                    } else {
                        averageGain = (averageGain!! * (period - 1) + maxOf(change, 0.0)) / period
                        averageLoss = (averageLoss!! * (period - 1) + maxOf(-change, 0.0)) / period
                    }
                    rsiValue(averageGain!!, averageLoss!!)
                }
            }
            val values = context.previousResult.figures[figureIndex].values.persistentReplaceRange(start, context.oldBars.size, changed, context)
            values to RsiTailState(period, beforeLastGain, beforeLastLoss, averageGain, averageLoss)
        }
        return KLineIndicatorResult(name, series, calculated.map { (values, state) -> lineFigure("RSI${state.period}", values) }, calculated.map { it.second })
    }
}

object KLineWrIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>) = positivePeriod(params.ifEmpty { defaultParams }.single())
    override val name = "WR"
    override val defaultParams = listOf(10.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(KLineIndicatorFigure("WR", "WR", KLineIndicatorFigureType.LINE))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 1) { "WR expects one period" }
        val period = positivePeriod(resolved.single())
        val values = bars.indices.map { index ->
            if (index + 1 < period) null else {
                val window = bars.subList(index - period + 1, index + 1)
                val low = window.minOf(KLineBar::low)
                val high = window.maxOf(KLineBar::high)
                if (high == low) 0.0 else finiteOrNull((high - bars[index].close) / (high - low) * 100.0)
            }
        }
        return indicatorResult(name, series, lineFigure("WR", values))
    }
}

internal fun exponentialMovingAverageNullable(values: List<Double?>, period: Int): List<Double?> {
    require(period > 0) { "period must be positive" }
    val alpha = 2.0 / (period + 1.0)
    var current: Double? = null
    return values.map { value ->
        if (value == null || !value.isFinite()) {
            current = null
            null
        } else {
            finiteOrNull(current?.let { alpha * value + (1.0 - alpha) * it } ?: value)
                .also { current = it }
        }
    }
}

internal fun chineseSmoothed(values: List<Double?>, period: Int, seed: Double): List<Double?> {
    require(period > 0) { "period must be positive" }
    var previous = seed
    return values.map { value ->
        value?.let { finiteOrNull((previous * (period - 1) + it) / period) }
            .also { if (it != null) previous = it }
    }
}

internal fun relativeStrengthIndex(close: List<Double>, period: Int): List<Double?> {
    return relativeStrengthIndexWithState(close, period).first
}

private data class RsiTailState(
    val period: Int,
    val beforeLastGain: Double?,
    val beforeLastLoss: Double?,
    val lastGain: Double?,
    val lastLoss: Double?,
)

private fun relativeStrengthIndexWithState(close: List<Double>, period: Int): Pair<List<Double?>, RsiTailState> {
    require(period > 0) { "period must be positive" }
    var averageGain: Double? = null
    var averageLoss: Double? = null
    var beforeLastGain: Double? = null
    var beforeLastLoss: Double? = null
    val values = close.indices.map { index ->
        beforeLastGain = averageGain
        beforeLastLoss = averageLoss
        if (index < period) return@map null
        val change = close[index] - close[index - 1]
        if (averageGain == null || averageLoss == null) {
            val changes = (index - period + 1..index).map { close[it] - close[it - 1] }
            averageGain = changes.sumOf { maxOf(it, 0.0) } / period
            averageLoss = changes.sumOf { maxOf(-it, 0.0) } / period
        } else {
            averageGain = (averageGain!! * (period - 1) + maxOf(change, 0.0)) / period
            averageLoss = (averageLoss!! * (period - 1) + maxOf(-change, 0.0)) / period
        }
        rsiValue(averageGain!!, averageLoss!!)
    }
    return values to RsiTailState(period, beforeLastGain, beforeLastLoss, averageGain, averageLoss)
}

private fun rsiValue(averageGain: Double, averageLoss: Double): Double? = when {
    averageLoss == 0.0 && averageGain == 0.0 -> 50.0
    averageLoss == 0.0 -> 100.0
    else -> finiteOrNull(100.0 - 100.0 / (1.0 + averageGain / averageLoss))
}
