package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

object KLineVolumeIndicator : KLineIndicatorTemplate {
    override val name = "VOL"
    override val defaultParams = listOf(5.0, 10.0)
    override val series = KLineIndicatorSeries.VOLUME
    override val figures = listOf(
        KLineIndicatorFigure("VOL", "VOL", KLineIndicatorFigureType.BAR),
        KLineIndicatorFigure("MA5", "MA5", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MA10", "MA10", KLineIndicatorFigureType.LINE),
    )

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

object KLineAmountIndicator : KLineIndicatorTemplate {
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

object KLineBbdIndicator : KLineIndicatorTemplate {
    override val name = "BBD"
    override val defaultParams = listOf(5.0, 5.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("BBD", "BBD", KLineIndicatorFigureType.BAR),
        KLineIndicatorFigure("BBD5", "BBD5", KLineIndicatorFigureType.LINE),
    )

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
