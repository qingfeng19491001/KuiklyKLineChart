package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

object KLineMovingAverageIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>) = params.ifEmpty { defaultParams }.maxOf(::positivePeriod)
    override val name: String = "MA"
    override val defaultParams: List<Double> = listOf(5.0, 10.0, 20.0, 30.0)
    override val series: KLineIndicatorSeries = KLineIndicatorSeries.PRICE
    override val figures: List<KLineIndicatorFigure> = defaultParams.map { period ->
        KLineIndicatorFigure(
            key = "MA${period.toInt()}",
            title = "MA${period.toInt()}",
            type = KLineIndicatorFigureType.LINE,
        )
    }
    override fun figureSchema(params: List<Double>) = params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
        KLineIndicatorFigure("MA$period", "MA$period", KLineIndicatorFigureType.LINE)
    }

    override fun calculate(
        bars: List<KLineBar>,
        params: List<Double>,
    ): KLineIndicatorResult {
        val periods = params.ifEmpty { defaultParams }.map(::positivePeriod)
        return KLineIndicatorResult(
            templateName = name,
            series = series,
            figures = periods.map { period ->
                KLineIndicatorFigureResult(
                    key = "MA$period",
                    type = KLineIndicatorFigureType.LINE,
                    values = movingAverage(bars.map(KLineBar::close), period),
                )
            },
        )
    }
}

internal fun positivePeriod(value: Double): Int {
    require(value.isFinite() && value > 0.0 && value <= MAX_INDICATOR_PERIOD && value == value.toInt().toDouble()) {
        "Indicator period must be a positive integer no greater than $MAX_INDICATOR_PERIOD"
    }
    return value.toInt()
}

private const val MAX_INDICATOR_PERIOD = 1_000

internal fun movingAverage(values: List<Double>, period: Int): List<Double?> {
    require(period > 0) { "period must be positive" }
    return values.indices.map { index ->
        if (index + 1 < period) null else {
            var sum = 0.0
            var valid = true
            for (windowIndex in index - period + 1..index) {
                val value = values[windowIndex]
                if (!value.isFinite()) valid = false else sum += value
            }
            if (!valid) null else finiteOrNull(sum / period)
        }
    }
}

internal fun finiteOrNull(value: Double): Double? = value.takeIf(Double::isFinite)
