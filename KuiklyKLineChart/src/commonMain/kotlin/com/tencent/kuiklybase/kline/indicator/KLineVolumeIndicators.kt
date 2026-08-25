package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

internal object KLineVolumeIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>) = params.ifEmpty { defaultParams }.maxOf(::positivePeriod)
    override val name = "VOL"
    override val defaultParams = listOf(5.0, 10.0)
    override val series = KLineIndicatorSeries.VOLUME
    override val figures = listOf(
        KLineIndicatorFigure("VOL", "VOL", KLineIndicatorFigureType.BAR),
        KLineIndicatorFigure("MA5", "MA5", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MA10", "MA10", KLineIndicatorFigureType.LINE),
    )
    override fun figureSchema(params: List<Double>) = listOf(
        KLineIndicatorFigure("VOL", "VOL", KLineIndicatorFigureType.BAR),
    ) + params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
        KLineIndicatorFigure("MA$period", "MA$period", KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val periods = params.ifEmpty { defaultParams }.map(::positivePeriod)
        val volume = bars.map(KLineBar::volume)
        return KLineIndicatorResult(
            name,
            series,
            listOf(barFigure("VOL", volume)) + periods.map { period ->
                lineFigure("MA$period", nullableMovingAverage(volume, period))
            },
        )
    }
}

internal object KLineAmountIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>) = 1
    override val name = "AMOUNT"
    override val defaultParams = emptyList<Double>()
    override val series = KLineIndicatorSeries.VOLUME
    override val figures = listOf(KLineIndicatorFigure("AMOUNT", "AMOUNT", KLineIndicatorFigureType.BAR))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        require(params.isEmpty()) { "AMOUNT does not accept params" }
        val values = bars.map { bar ->
            bar.turnover ?: bar.volume?.let { finiteOrNull(bar.close * it) }
        }
        return indicatorResult(name, series, barFigure("AMOUNT", values))
    }
}

internal object KLineBbdIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "BBD"
    override val defaultParams = listOf(5.0, 5.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("BBD", "BBD", KLineIndicatorFigureType.BAR),
        KLineIndicatorFigure("BBD5", "BBD5", KLineIndicatorFigureType.LINE),
    )
    override fun figureSchema(params: List<Double>): List<KLineIndicatorFigure> {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "BBD expects average and signal periods" }
        val signal = positivePeriod(resolved[1])
        return listOf(
            KLineIndicatorFigure("BBD", "BBD", KLineIndicatorFigureType.BAR),
            KLineIndicatorFigure("BBD$signal", "BBD$signal", KLineIndicatorFigureType.LINE),
        )
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "BBD expects average and signal periods" }
        val averagePeriod = positivePeriod(resolved[0])
        val signalPeriod = positivePeriod(resolved[1])
        val average = movingAverage(bars.map(KLineBar::close), averagePeriod)
        val bbd = bars.indices.map { index ->
            val mean = average[index]
            val volume = bars[index].volume
            if (mean == null || mean == 0.0 || volume == null) null
            else finiteOrNull(volume * (bars[index].close - mean) / mean)
        }
        return indicatorResult(
            name,
            series,
            barFigure("BBD", bbd),
            lineFigure("BBD$signalPeriod", exponentialMovingAverageNullable(bbd, signalPeriod)),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val resolved = params.ifEmpty { defaultParams }
        val averagePeriod = positivePeriod(resolved[0])
        val signalPeriod = positivePeriod(resolved[1])
        val start = context.change.newAffectedRange.first
        val changedBbd = (start until context.newBars.size).map { index ->
            val windowStart = index - averagePeriod + 1
            if (windowStart < 0) null else {
                val mean = context.newBars.subList(windowStart, index + 1).map(KLineBar::close).average()
                val bar = context.newBars[index]
                if (mean == 0.0 || bar.volume == null) null else finiteOrNull(bar.volume * (bar.close - mean) / mean)
            }
        }
        val oldBbd = context.previousResult.figures[0].values
        val bbd = oldBbd.persistentReplaceRange(start, context.oldBars.size, changedBbd, context)
        val alpha = 2.0 / (signalPeriod + 1.0)
        var signal = context.previousResult.figures[1].values.getOrNull(start - 1)
        val changedSignal = changedBbd.map { value ->
            if (value == null || !value.isFinite()) null.also { signal = null }
            else finiteOrNull(signal?.let { alpha * value + (1.0 - alpha) * it } ?: value).also { signal = it }
        }
        return indicatorResult(
            name, series,
            barFigure("BBD", bbd),
            lineFigure("BBD$signalPeriod", context.previousResult.figures[1].values.persistentReplaceRange(start, context.oldBars.size, changedSignal, context)),
        )
    }
}

internal fun nullableMovingAverage(values: List<Double?>, period: Int): List<Double?> {
    require(period > 0) { "period must be positive" }
    return values.indices.map { index ->
        val start = index - period + 1
        if (start < 0) null else values.subList(start, index + 1)
            .takeIf { window -> window.all { it?.isFinite() == true } }
            ?.filterNotNull()?.average()?.let(::finiteOrNull)
    }
}
