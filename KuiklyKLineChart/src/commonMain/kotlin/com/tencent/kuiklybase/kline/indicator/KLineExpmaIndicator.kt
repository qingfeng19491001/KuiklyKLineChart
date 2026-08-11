package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

object KLineExpmaIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "EXPMA"
    override val defaultParams = listOf(12.0, 50.0)
    override val series = KLineIndicatorSeries.PRICE
    override val figures = defaultParams.map { period ->
        val key = "EXPMA${period.toInt()}"
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }
    override fun figureSchema(params: List<Double>) = params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
        KLineIndicatorFigure("EXPMA$period", "EXPMA$period", KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult =
        KLineIndicatorResult(
            templateName = name,
            series = series,
            figures = params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
                lineFigure("EXPMA$period", exponentialMovingAverage(bars.map(KLineBar::close), period))
            },
        )

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val start = context.change.newAffectedRange.first
        val periods = params.ifEmpty { defaultParams }.map(::positivePeriod)
        return KLineIndicatorResult(name, series, periods.mapIndexed { figureIndex, period ->
            val alpha = 2.0 / (period + 1.0)
            var previous = context.previousResult.figures[figureIndex].values.getOrNull(start - 1)
            val changed = context.newBars.subList(start, context.newBars.size).map { bar ->
                finiteOrNull(previous?.let { alpha * bar.close + (1.0 - alpha) * it } ?: bar.close).also { previous = it }
            }
            lineFigure("EXPMA$period", context.previousResult.figures[figureIndex].values.persistentReplaceRange(start, context.oldBars.size, changed, context))
        })
    }
}

internal fun exponentialMovingAverage(values: List<Double>, period: Int): List<Double?> {
    require(period > 0) { "period must be positive" }
    val alpha = 2.0 / (period + 1.0)
    var current: Double? = null
    return values.map { value ->
        if (!value.isFinite()) {
            current = null
            null
        } else {
            finiteOrNull(current?.let { alpha * value + (1.0 - alpha) * it } ?: value)
                .also { current = it }
        }
    }
}
