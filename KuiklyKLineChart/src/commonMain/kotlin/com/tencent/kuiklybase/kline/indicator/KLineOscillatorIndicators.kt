package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

object KLineMacdIndicator : KLineIndicatorTemplate {
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
        return indicatorResult(
            name,
            series,
            lineFigure("DIFF", diff),
            lineFigure("DEA", dea),
            barFigure("MACD", combine(diff, dea) { value, signal -> (value - signal) * 2.0 }),
        )
    }
}

object KLineKdjIndicator : KLineIndicatorTemplate {
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
}

object KLineRsiIndicator : KLineIndicatorTemplate {
    override val name = "RSI"
    override val defaultParams = listOf(6.0, 12.0, 24.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = defaultParams.map { period ->
        val key = "RSI${period.toInt()}"
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult =
        KLineIndicatorResult(
            name,
            series,
            params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
                lineFigure("RSI$period", relativeStrengthIndex(bars.map(KLineBar::close), period))
            },
        )
}

object KLineWrIndicator : KLineIndicatorTemplate {
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
    require(period > 0) { "period must be positive" }
    var averageGain: Double? = null
    var averageLoss: Double? = null
    return close.indices.map { index ->
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
        when {
            averageLoss == 0.0 && averageGain == 0.0 -> 50.0
            averageLoss == 0.0 -> 100.0
            else -> finiteOrNull(100.0 - 100.0 / (1.0 + averageGain!! / averageLoss!!))
        }
    }
}
