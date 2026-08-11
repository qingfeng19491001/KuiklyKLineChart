package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

object KLineBbiIndicator : KLineIndicatorTemplate {
    override val name = "BBI"
    override val defaultParams = listOf(3.0, 6.0, 12.0, 24.0)
    override val series = KLineIndicatorSeries.PRICE
    override val figures = listOf(KLineIndicatorFigure("BBI", "BBI", KLineIndicatorFigureType.LINE))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val periods = params.ifEmpty { defaultParams }.map(::positivePeriod)
        require(periods.isNotEmpty()) { "BBI expects at least one period" }
        val averages = periods.map { movingAverage(bars.map(KLineBar::close), it) }
        val values = bars.indices.map { index ->
            averages.map { it[index] }.takeIf { items -> items.all { it != null } }
                ?.filterNotNull()?.average()?.let(::finiteOrNull)
        }
        return indicatorResult(name, series, lineFigure("BBI", values))
    }
}

object KLineEneIndicator : KLineIndicatorTemplate {
    override val name = "ENE"
    override val defaultParams = listOf(10.0, 11.0, 9.0)
    override val series = KLineIndicatorSeries.PRICE
    override val figures = listOf("UPPER", "ENE", "LOWER").map { key ->
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 3) { "ENE expects period, upper percent and lower percent" }
        val middle = movingAverage(bars.map(KLineBar::close), positivePeriod(resolved[0]))
        val upperRatio = resolved[1].also { require(it.isFinite() && it >= 0.0) } / 100.0
        val lowerRatio = resolved[2].also { require(it.isFinite() && it >= 0.0) } / 100.0
        return indicatorResult(
            name,
            series,
            lineFigure("UPPER", middle.map { it?.let { value -> finiteOrNull(value * (1.0 + upperRatio)) } }),
            lineFigure("ENE", middle),
            lineFigure("LOWER", middle.map { it?.let { value -> finiteOrNull(value * (1.0 - lowerRatio)) } }),
        )
    }
}
