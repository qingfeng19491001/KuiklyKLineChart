package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

object KLineMovingAverageIndicator : KLineIndicatorTemplate {
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
    require(value.isFinite() && value > 0.0 && value == value.toInt().toDouble()) {
        "Indicator period must be a positive integer"
    }
    return value.toInt()
}

internal fun movingAverage(values: List<Double>, period: Int): List<Double?> {
    require(period > 0) { "period must be positive" }
    var sum = 0.0
    var invalidCount = 0
    return values.indices.map { index ->
        val added = values[index]
        if (added.isFinite()) sum += added else invalidCount++
        if (index >= period) {
            val removed = values[index - period]
            if (removed.isFinite()) sum -= removed else invalidCount--
        }
        if (index + 1 < period || invalidCount > 0) null else finiteOrNull(sum / period)
    }
}

internal fun finiteOrNull(value: Double): Double? = value.takeIf(Double::isFinite)
