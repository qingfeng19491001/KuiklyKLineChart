package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar
import kotlin.math.abs
import kotlin.math.max

internal object KLineSmaIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>) = params.ifEmpty { defaultParams }.maxOf(::positivePeriod)
    override val name = "SMA"
    override val defaultParams = listOf(5.0, 10.0, 20.0)
    override val series = KLineIndicatorSeries.PRICE
    override val figures = defaultParams.map { period ->
        val key = "SMA${period.toInt()}"
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }
    override fun figureSchema(params: List<Double>) = params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
        KLineIndicatorFigure("SMA$period", "SMA$period", KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val periods = params.ifEmpty { defaultParams }.map(::positivePeriod)
        return KLineIndicatorResult(
            name,
            series,
            periods.map { period -> lineFigure("SMA$period", movingAverage(bars.map(KLineBar::close), period)) },
        )
    }
}

internal object KLineEmaIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "EMA"
    override val defaultParams = listOf(12.0, 26.0)
    override val series = KLineIndicatorSeries.PRICE
    override val figures = defaultParams.map { period ->
        val key = "EMA${period.toInt()}"
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }
    override fun figureSchema(params: List<Double>) = params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
        KLineIndicatorFigure("EMA$period", "EMA$period", KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult =
        KLineIndicatorResult(
            name,
            series,
            params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
                lineFigure("EMA$period", exponentialMovingAverage(bars.map(KLineBar::close), period))
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
            lineFigure(
                "EMA$period",
                context.previousResult.figures[figureIndex].values.persistentReplaceRange(start, context.oldBars.size, changed, context),
            )
        })
    }
}

internal object KLineSarIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "SAR"
    override val defaultParams = listOf(2.0, 2.0, 20.0)
    override val series = KLineIndicatorSeries.PRICE
    override val figures = listOf(KLineIndicatorFigure("SAR", "SAR", KLineIndicatorFigureType.POINT))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val (startAf, step, maxAf) = sarParams(params)
        val values = MutableList<Double?>(bars.size) { null }
        if (bars.isEmpty()) return indicatorResult(name, series, pointFigure("SAR", values))
        var long = true
        var af = startAf
        var ep = bars[0].high
        var sar = bars[0].low
        values[0] = finiteOrNull(sar)
        var beforeLast = SarTailState(null, null, null, true, sar, ep, af, long)
        var last = beforeLast
        for (index in 1 until bars.size) {
            beforeLast = last
            val bar = bars[index]
            sar = (sar + af * (ep - sar)).let { next ->
                if (long) {
                    minOf(next, bars[index - 1].low, bars.getOrNull(index - 2)?.low ?: bars[index - 1].low)
                } else {
                    maxOf(next, bars[index - 1].high, bars.getOrNull(index - 2)?.high ?: bars[index - 1].high)
                }
            }
            if (long) {
                if (bar.low < sar) {
                    long = false
                    sar = ep
                    ep = bar.low
                    af = startAf
                } else {
                    if (bar.high > ep) {
                        ep = bar.high
                        af = minOf(af + step, maxAf)
                    }
                }
            } else {
                if (bar.high > sar) {
                    long = true
                    sar = ep
                    ep = bar.high
                    af = startAf
                } else {
                    if (bar.low < ep) {
                        ep = bar.low
                        af = minOf(af + step, maxAf)
                    }
                }
            }
            values[index] = finiteOrNull(sar)
            last = SarTailState(beforeLast.lastSar, beforeLast.lastEp, beforeLast.lastAf, beforeLast.lastLong, sar, ep, af, long)
        }
        return KLineIndicatorResult(name, series, listOf(pointFigure("SAR", values)), last)
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val start = context.change.newAffectedRange.first
        if (start <= 0) return calculate(context.newBars, params)
        val (startAf, step, maxAf) = sarParams(params)
        val previous = context.previousResult.calculationState as? SarTailState ?: return calculate(context.newBars, params)
        var sar = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeLastSar ?: previous.lastSar else previous.lastSar
        var ep = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeLastEp ?: previous.lastEp else previous.lastEp
        var af = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeLastAf ?: previous.lastAf else previous.lastAf
        var long = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeLastLong else previous.lastLong
        if (sar == null || ep == null || af == null) return calculate(context.newBars, params)
        var beforeLast = previous
        val changed = (start until context.newBars.size).map { index ->
            val bar = context.newBars[index]
            val prevLow = context.newBars[index - 1].low
            val prevHigh = context.newBars[index - 1].high
            val prev2Low = context.newBars.getOrNull(index - 2)?.low ?: prevLow
            val prev2High = context.newBars.getOrNull(index - 2)?.high ?: prevHigh
            beforeLast = SarTailState(sar, ep, af, long, sar, ep, af, long)
            sar = (sar!! + af!! * (ep!! - sar!!)).let { next ->
                if (long) minOf(next, prevLow, prev2Low) else maxOf(next, prevHigh, prev2High)
            }
            if (long) {
                if (bar.low < sar!!) {
                    long = false
                    sar = ep
                    ep = bar.low
                    af = startAf
                } else if (bar.high > ep!!) {
                    ep = bar.high
                    af = minOf(af!! + step, maxAf)
                }
            } else {
                if (bar.high > sar!!) {
                    long = true
                    sar = ep
                    ep = bar.high
                    af = startAf
                } else if (bar.low < ep!!) {
                    ep = bar.low
                    af = minOf(af!! + step, maxAf)
                }
            }
            finiteOrNull(sar!!)
        }
        val values = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changed, context)
        return KLineIndicatorResult(
            name,
            series,
            listOf(pointFigure("SAR", values)),
            SarTailState(beforeLast.lastSar, beforeLast.lastEp, beforeLast.lastAf, beforeLast.lastLong, sar, ep, af, long),
        )
    }

    private fun sarParams(params: List<Double>): Triple<Double, Double, Double> {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 3) { "SAR expects start, increment and max acceleration factors" }
        fun factor(value: Double): Double {
            require(value.isFinite() && value > 0.0) { "SAR acceleration factor must be positive" }
            return if (value >= 1.0) value / 100.0 else value
        }
        val start = factor(resolved[0])
        val step = factor(resolved[1])
        val max = factor(resolved[2])
        require(max >= start) { "SAR max acceleration must be at least the start factor" }
        return Triple(start, step, max)
    }
}

private data class SarTailState(
    val beforeLastSar: Double?,
    val beforeLastEp: Double?,
    val beforeLastAf: Double?,
    val beforeLastLong: Boolean,
    val lastSar: Double?,
    val lastEp: Double?,
    val lastAf: Double?,
    val lastLong: Boolean,
)

internal object KLineCciIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>) = positivePeriod(params.ifEmpty { defaultParams }.single())
    override val name = "CCI"
    override val defaultParams = listOf(14.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(KLineIndicatorFigure("CCI", "CCI", KLineIndicatorFigureType.LINE))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 1) { "CCI expects one period" }
        val period = positivePeriod(resolved.single())
        val typical = bars.map { (it.high + it.low + it.close) / 3.0 }
        val values = typical.indices.map { index ->
            if (index + 1 < period) null else {
                val window = typical.subList(index - period + 1, index + 1)
                val mean = window.average()
                val deviation = window.sumOf { abs(it - mean) } / period
                if (deviation == 0.0) 0.0 else finiteOrNull((typical[index] - mean) / (0.015 * deviation))
            }
        }
        return indicatorResult(name, series, lineFigure("CCI", values))
    }
}

internal object KLineDmiIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "DMI"
    override val defaultParams = listOf(14.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf("PDI", "MDI", "ADX").map { key ->
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 1) { "DMI expects one period" }
        val period = positivePeriod(resolved.single())
        val pdi = MutableList<Double?>(bars.size) { null }
        val mdi = MutableList<Double?>(bars.size) { null }
        val adx = MutableList<Double?>(bars.size) { null }
        if (bars.size <= period) return indicatorResult(name, series, lineFigure("PDI", pdi), lineFigure("MDI", mdi), lineFigure("ADX", adx))
        var smoothTr = 0.0
        var smoothPlus = 0.0
        var smoothMinus = 0.0
        var smoothDx: Double? = null
        var beforeLast = DmiTailState(period, 0.0, 0.0, 0.0, null, 0.0, 0.0, 0.0, null)
        var last = beforeLast
        for (index in 1 until bars.size) {
            val (tr, plus, minus) = directionalMove(bars[index - 1], bars[index])
            if (index <= period) {
                smoothTr += tr
                smoothPlus += plus
                smoothMinus += minus
                if (index < period) continue
            } else {
                smoothTr = smoothTr - smoothTr / period + tr
                smoothPlus = smoothPlus - smoothPlus / period + plus
                smoothMinus = smoothMinus - smoothMinus / period + minus
            }
            val plusDi = if (smoothTr == 0.0) 0.0 else 100.0 * smoothPlus / smoothTr
            val minusDi = if (smoothTr == 0.0) 0.0 else 100.0 * smoothMinus / smoothTr
            pdi[index] = finiteOrNull(plusDi)
            mdi[index] = finiteOrNull(minusDi)
            val dx = if (plusDi + minusDi == 0.0) 0.0 else 100.0 * abs(plusDi - minusDi) / (plusDi + minusDi)
            smoothDx = smoothDx?.let { it - it / period + dx } ?: dx
            if (index >= period * 2 - 1) adx[index] = finiteOrNull(smoothDx)
            beforeLast = last
            last = DmiTailState(period, beforeLast.smoothTr, beforeLast.smoothPlus, beforeLast.smoothMinus, beforeLast.smoothDx, smoothTr, smoothPlus, smoothMinus, smoothDx)
        }
        return KLineIndicatorResult(name, series, listOf(lineFigure("PDI", pdi), lineFigure("MDI", mdi), lineFigure("ADX", adx)), last)
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val start = context.change.newAffectedRange.first
        if (start <= 0) return calculate(context.newBars, params)
        val previous = context.previousResult.calculationState as? DmiTailState ?: return calculate(context.newBars, params)
        val period = previous.period
        var smoothTr = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeLastTr else previous.smoothTr
        var smoothPlus = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeLastPlus else previous.smoothPlus
        var smoothMinus = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeLastMinus else previous.smoothMinus
        var smoothDx = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeLastDx else previous.smoothDx
        var beforeLast = previous
        val changedPdi = mutableListOf<Double?>()
        val changedMdi = mutableListOf<Double?>()
        val changedAdx = mutableListOf<Double?>()
        (start until context.newBars.size).forEach { index ->
            val (tr, plus, minus) = directionalMove(context.newBars[index - 1], context.newBars[index])
            beforeLast = DmiTailState(period, smoothTr, smoothPlus, smoothMinus, smoothDx, smoothTr, smoothPlus, smoothMinus, smoothDx)
            if (index < period) {
                changedPdi += null
                changedMdi += null
                changedAdx += null
                return@forEach
            }
            if (index == period) {
                // First completed Wilder window: replay the prefix sums from bars.
                val prefix = calculate(context.newBars.subList(0, index + 1), params)
                changedPdi += prefix.figures[0].values.last()
                changedMdi += prefix.figures[1].values.last()
                changedAdx += prefix.figures[2].values.last()
                val state = prefix.calculationState as? DmiTailState ?: return calculate(context.newBars, params)
                smoothTr = state.smoothTr
                smoothPlus = state.smoothPlus
                smoothMinus = state.smoothMinus
                smoothDx = state.smoothDx
                return@forEach
            }
            smoothTr = smoothTr - smoothTr / period + tr
            smoothPlus = smoothPlus - smoothPlus / period + plus
            smoothMinus = smoothMinus - smoothMinus / period + minus
            val plusDi = if (smoothTr == 0.0) 0.0 else 100.0 * smoothPlus / smoothTr
            val minusDi = if (smoothTr == 0.0) 0.0 else 100.0 * smoothMinus / smoothTr
            val dx = if (plusDi + minusDi == 0.0) 0.0 else 100.0 * abs(plusDi - minusDi) / (plusDi + minusDi)
            smoothDx = smoothDx?.let { it - it / period + dx } ?: dx
            changedPdi += finiteOrNull(plusDi)
            changedMdi += finiteOrNull(minusDi)
            changedAdx += if (index >= period * 2 - 1) finiteOrNull(smoothDx) else null
        }
        return KLineIndicatorResult(
            name,
            series,
            listOf(
                lineFigure("PDI", context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changedPdi, context)),
                lineFigure("MDI", context.previousResult.figures[1].values.persistentReplaceRange(start, context.oldBars.size, changedMdi, context)),
                lineFigure("ADX", context.previousResult.figures[2].values.persistentReplaceRange(start, context.oldBars.size, changedAdx, context)),
            ),
            DmiTailState(period, beforeLast.smoothTr, beforeLast.smoothPlus, beforeLast.smoothMinus, beforeLast.smoothDx, smoothTr, smoothPlus, smoothMinus, smoothDx),
        )
    }
}

private data class DmiTailState(
    val period: Int,
    val beforeLastTr: Double,
    val beforeLastPlus: Double,
    val beforeLastMinus: Double,
    val beforeLastDx: Double?,
    val smoothTr: Double,
    val smoothPlus: Double,
    val smoothMinus: Double,
    val smoothDx: Double?,
)

private fun directionalMove(previous: KLineBar, current: KLineBar): Triple<Double, Double, Double> {
    val up = current.high - previous.high
    val down = previous.low - current.low
    val plus = if (up > down && up > 0.0) up else 0.0
    val minus = if (down > up && down > 0.0) down else 0.0
    val tr = max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close)))
    return Triple(tr, plus, minus)
}

internal object KLineObvIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "OBV"
    override val defaultParams = emptyList<Double>()
    override val series = KLineIndicatorSeries.VOLUME
    override val figures = listOf(KLineIndicatorFigure("OBV", "OBV", KLineIndicatorFigureType.LINE))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        require(params.isEmpty()) { "OBV does not accept params" }
        var obv = 0.0
        val values = bars.indices.map { index ->
            val volume = bars[index].volume ?: 0.0
            obv += when {
                index == 0 -> volume
                bars[index].close > bars[index - 1].close -> volume
                bars[index].close < bars[index - 1].close -> -volume
                else -> 0.0
            }
            finiteOrNull(obv)
        }
        return indicatorResult(name, series, lineFigure("OBV", values))
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val start = context.change.newAffectedRange.first
        var obv = if (start == 0) 0.0 else context.previousResult.figures[0].values.getOrNull(start - 1) ?: 0.0
        val changed = (start until context.newBars.size).map { index ->
            val volume = context.newBars[index].volume ?: 0.0
            obv += when {
                index == 0 -> volume
                context.newBars[index].close > context.newBars[index - 1].close -> volume
                context.newBars[index].close < context.newBars[index - 1].close -> -volume
                else -> 0.0
            }
            finiteOrNull(obv)
        }
        return indicatorResult(
            name,
            series,
            lineFigure(
                "OBV",
                context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changed, context),
            ),
        )
    }
}

internal object KLineBiasIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>) = params.ifEmpty { defaultParams }.maxOf(::positivePeriod)
    override val name = "BIAS"
    override val defaultParams = listOf(6.0, 12.0, 24.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = defaultParams.map { period ->
        val key = "BIAS${period.toInt()}"
        KLineIndicatorFigure(key, key, KLineIndicatorFigureType.LINE)
    }
    override fun figureSchema(params: List<Double>) = params.ifEmpty { defaultParams }.map(::positivePeriod).map { period ->
        KLineIndicatorFigure("BIAS$period", "BIAS$period", KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val periods = params.ifEmpty { defaultParams }.map(::positivePeriod)
        val close = bars.map(KLineBar::close)
        return KLineIndicatorResult(
            name,
            series,
            periods.map { period ->
                val mean = movingAverage(close, period)
                lineFigure(
                    "BIAS$period",
                    close.zip(mean) { price, average ->
                        if (average == null || average == 0.0) null else finiteOrNull((price - average) / average * 100.0)
                    },
                )
            },
        )
    }
}

internal object KLineRocIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        val period = positivePeriod(resolved[0])
        val signal = resolved.getOrNull(1)?.let(::positivePeriod) ?: 0
        return period + signal + 1
    }
    override val name = "ROC"
    override val defaultParams = listOf(12.0, 6.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("ROC", "ROC", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MAROC", "MAROC", KLineIndicatorFigureType.LINE),
    )
    override fun figureSchema(params: List<Double>): List<KLineIndicatorFigure> {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size in 1..2) { "ROC expects period and optional signal period" }
        return listOf(KLineIndicatorFigure("ROC", "ROC", KLineIndicatorFigureType.LINE)) +
            if (resolved.size > 1) listOf(KLineIndicatorFigure("MAROC", "MAROC", KLineIndicatorFigureType.LINE)) else emptyList()
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size in 1..2) { "ROC expects period and optional signal period" }
        val period = positivePeriod(resolved[0])
        val values = bars.indices.map { index ->
            if (index < period) null else {
                val previous = bars[index - period].close
                if (previous == 0.0) null else finiteOrNull((bars[index].close - previous) / previous * 100.0)
            }
        }
        val figures = mutableListOf(lineFigure("ROC", values))
        if (resolved.size > 1) figures += lineFigure("MAROC", nullableMovingAverage(values, positivePeriod(resolved[1])))
        return KLineIndicatorResult(name, series, figures)
    }
}
