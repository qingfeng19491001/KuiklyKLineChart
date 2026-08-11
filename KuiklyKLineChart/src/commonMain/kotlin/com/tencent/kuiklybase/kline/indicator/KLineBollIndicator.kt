package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar
import kotlin.math.sqrt

object KLineBollIndicator : KLineIndicatorTemplate {
    override val name = "BOLL"
    override val defaultParams = listOf(20.0, 2.0)
    override val series = KLineIndicatorSeries.PRICE
    override val figures = listOf("BOLL", "UP", "DN").map { key ->
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "BOLL expects period and multiplier" }
        val period = positivePeriod(resolved[0])
        val multiplier = resolved[1].also { require(it.isFinite() && it >= 0.0) }
        val close = bars.map(KLineBar::close)
        var sum = 0.0
        var sumSquares = 0.0
        val middle = MutableList<Double?>(close.size) { null }
        val deviation = MutableList<Double?>(close.size) { null }
        close.indices.forEach { index ->
            val added = close[index]
            sum += added
            sumSquares += added * added
            if (index >= period) {
                val removed = close[index - period]
                sum -= removed
                sumSquares -= removed * removed
            }
            if (index + 1 >= period) {
                val mean = sum / period
                middle[index] = finiteOrNull(mean)
                deviation[index] = finiteOrNull(sqrt((sumSquares / period - mean * mean).coerceAtLeast(0.0)))
            }
        }
        return indicatorResult(
            name,
            series,
            lineFigure("BOLL", middle),
            lineFigure("UP", combine(middle, deviation) { mid, sd -> mid + multiplier * sd }),
            lineFigure("DN", combine(middle, deviation) { mid, sd -> mid - multiplier * sd }),
        )
    }
}

internal fun indicatorResult(
    templateName: String,
    series: KLineIndicatorSeries,
    vararg figures: KLineIndicatorFigureResult,
) = KLineIndicatorResult(templateName, series, figures.toList())

internal fun lineFigure(key: String, values: List<Double?>) =
    KLineIndicatorFigureResult(key, KLineIndicatorFigureType.LINE, values)

internal fun barFigure(key: String, values: List<Double?>) =
    KLineIndicatorFigureResult(key, KLineIndicatorFigureType.BAR, values)

internal inline fun combine(
    first: List<Double?>,
    second: List<Double?>,
    operation: (Double, Double) -> Double,
): List<Double?> = first.zip(second).map { (left, right) ->
    if (left == null || right == null) null else finiteOrNull(operation(left, right))
}
