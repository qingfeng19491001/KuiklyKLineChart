package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar
import kotlin.math.ceil
import kotlin.math.max

internal object KLineBrarIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>) = positivePeriod(params.ifEmpty { defaultParams }.single()) + 1
    override val name = "BRAR"
    override val defaultParams = listOf(26.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("BR", "BR", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("AR", "AR", KLineIndicatorFigureType.LINE),
    )

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val period = brarPeriod(params)
        val br = MutableList<Double?>(bars.size) { null }
        val ar = MutableList<Double?>(bars.size) { null }
        val window = ArrayDeque<BrarPart>(period)
        var previous: KLineBar? = null
        bars.forEachIndexed { index, bar ->
            val prevClose = previous?.close ?: bar.close
            window.addLast(BrarPart(bar.high - bar.open, bar.open - bar.low, bar.high - prevClose, prevClose - bar.low))
            if (window.size > period) window.removeFirst()
            if (window.size == period) {
                var ho = 0.0
                var ol = 0.0
                var hcy = 0.0
                var cyl = 0.0
                window.forEach { part ->
                    ho += part.ho
                    ol += part.ol
                    hcy += part.hcy
                    cyl += part.cyl
                }
                ar[index] = if (ol == 0.0) 0.0 else finiteOrNull(ho / ol * 100.0)
                br[index] = if (cyl == 0.0) 0.0 else finiteOrNull(hcy / cyl * 100.0)
            }
            previous = bar
        }
        return indicatorResult(name, series, lineFigure("BR", br), lineFigure("AR", ar))
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) return super.calculateIncremental(context, params)
        val period = brarPeriod(params)
        val start = context.change.newAffectedRange.first
        val br = ArrayList<Double?>(context.newBars.size - start)
        val ar = ArrayList<Double?>(context.newBars.size - start)
        for (index in start until context.newBars.size) {
            val (brValue, arValue) = brarValues(context.newBars, index, period)
            br += brValue
            ar += arValue
        }
        return spliceTailFigures(name, series, context, listOf(lineFigure("BR", br), lineFigure("AR", ar)))
    }

    private fun brarPeriod(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 1) { "BRAR expects one period" }
        return positivePeriod(resolved.single())
    }
}

private fun brarValues(bars: List<KLineBar>, index: Int, period: Int): Pair<Double?, Double?> {
    if (index + 1 < period) return null to null
    var ho = 0.0
    var ol = 0.0
    var hcy = 0.0
    var cyl = 0.0
    for (window in index - period + 1..index) {
        val bar = bars[window]
        val prevClose = bars.getOrNull(window - 1)?.close ?: bar.close
        ho += bar.high - bar.open
        ol += bar.open - bar.low
        hcy += bar.high - prevClose
        cyl += prevClose - bar.low
    }
    val ar = if (ol == 0.0) 0.0 else finiteOrNull(ho / ol * 100.0)
    val br = if (cyl == 0.0) 0.0 else finiteOrNull(hcy / cyl * 100.0)
    return br to ar
}

internal object KLineCrIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 5) { "CR expects period and four MA periods" }
        val period = positivePeriod(resolved[0])
        val extra = resolved.drop(1).maxOf { value ->
            val ma = positivePeriod(value)
            ma + crShift(ma)
        }
        return period + extra + 2
    }
    override val name = "CR"
    override val defaultParams = listOf(26.0, 10.0, 20.0, 40.0, 60.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf("CR", "MA1", "MA2", "MA3", "MA4").map {
        KLineIndicatorFigure(it, it, KLineIndicatorFigureType.LINE)
    }

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 5) { "CR expects period and four MA periods" }
        val period = positivePeriod(resolved[0])
        val cr = MutableList<Double?>(bars.size) { null }
        val window = ArrayDeque<Pair<Double, Double>>(period)
        var previous: KLineBar? = null
        bars.forEachIndexed { index, bar ->
            val midSource = previous ?: bar
            val mid = (midSource.high + midSource.low) / 2.0
            val highPart = max(0.0, bar.high - mid)
            val midPart = max(0.0, mid - bar.low)
            window.addLast(highPart to midPart)
            if (window.size > period) window.removeFirst()
            if (window.size == period) {
                var highSum = 0.0
                var midSum = 0.0
                window.forEach { part ->
                    highSum += part.first
                    midSum += part.second
                }
                cr[index] = if (midSum == 0.0) 0.0 else finiteOrNull(highSum / midSum * 100.0)
            }
            previous = bar
        }
        val mas = resolved.drop(1).mapIndexed { figureIndex, value ->
            val maPeriod = positivePeriod(value)
            val average = nullableMovingAverage(cr, maPeriod)
            val shift = crShift(maPeriod)
            lineFigure(
                "MA${figureIndex + 1}",
                average.indices.map { index -> if (index < shift) null else average[index - shift] },
            )
        }
        return KLineIndicatorResult(name, series, listOf(lineFigure("CR", cr)) + mas)
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) return super.calculateIncremental(context, params)
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 5) { "CR expects period and four MA periods" }
        val period = positivePeriod(resolved[0])
        val start = context.change.newAffectedRange.first
        val changedCr = (start until context.newBars.size).map { index -> crValue(context.newBars, index, period) }
        val cr = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changedCr, context)
        val mas = resolved.drop(1).mapIndexed { figureIndex, value ->
            val maPeriod = positivePeriod(value)
            val shift = crShift(maPeriod)
            val changed = (start until cr.size).map { index ->
                val source = index - shift
                if (source < 0) null else cr.finiteWindowAverage(source - maPeriod + 1, source)
            }
            lineFigure(
                "MA${figureIndex + 1}",
                context.previousResult.figures[figureIndex + 1].values.persistentReplaceRange(start, context.oldBars.size, changed, context),
            )
        }
        return KLineIndicatorResult(name, series, listOf(lineFigure("CR", cr)) + mas)
    }
}

private fun crShift(period: Int): Int = ceil(period / 2.5 + 1.0).toInt()

private fun crValue(bars: List<KLineBar>, index: Int, period: Int): Double? {
    if (index + 1 < period) return null
    var highSub = 0.0
    var midSub = 0.0
    for (window in index - period + 1..index) {
        val bar = bars[window]
        val prev = bars.getOrNull(window - 1) ?: bar
        val mid = (prev.high + prev.low) / 2.0
        highSub += max(0.0, bar.high - mid)
        midSub += max(0.0, mid - bar.low)
    }
    return if (midSub == 0.0) 0.0 else finiteOrNull(highSub / midSub * 100.0)
}

internal object KLineDmaIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 3) { "DMA expects short, long and signal periods" }
        return resolved.maxOf(::positivePeriod) + positivePeriod(resolved[2])
    }
    override val name = "DMA"
    override val defaultParams = listOf(10.0, 50.0, 10.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("DMA", "DMA", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("AMA", "AMA", KLineIndicatorFigureType.LINE),
    )

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 3) { "DMA expects short, long and signal periods" }
        val short = movingAverage(bars.map(KLineBar::close), positivePeriod(resolved[0]))
        val long = movingAverage(bars.map(KLineBar::close), positivePeriod(resolved[1]))
        val dma = combine(short, long) { left, right -> left - right }
        return indicatorResult(
            name,
            series,
            lineFigure("DMA", dma),
            lineFigure("AMA", nullableMovingAverage(dma, positivePeriod(resolved[2]))),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) return super.calculateIncremental(context, params)
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 3) { "DMA expects short, long and signal periods" }
        val shortPeriod = positivePeriod(resolved[0])
        val longPeriod = positivePeriod(resolved[1])
        val signalPeriod = positivePeriod(resolved[2])
        val start = context.change.newAffectedRange.first
        val changedDma = (start until context.newBars.size).map { index ->
            val short = context.newBars.closeAverage(index - shortPeriod + 1, index) ?: return@map null
            val long = context.newBars.closeAverage(index - longPeriod + 1, index) ?: return@map null
            finiteOrNull(short - long)
        }
        val dma = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changedDma, context)
        val changedAma = (start until dma.size).map { index -> dma.finiteWindowAverage(index - signalPeriod + 1, index) }
        return spliceTailFigures(
            name,
            series,
            context,
            listOf(lineFigure("DMA", changedDma), lineFigure("AMA", changedAma)),
        )
    }
}

internal object KLineEmvIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "EMV expects period and signal period" }
        return resolved.sumOf(::positivePeriod) + 1
    }
    override val name = "EMV"
    override val defaultParams = listOf(14.0, 9.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("EMV", "EMV", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MAEMV", "MAEMV", KLineIndicatorFigureType.LINE),
    )

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "EMV expects period and signal period" }
        val daily = bars.indices.map { index ->
            if (index == 0) null else {
                val bar = bars[index]
                val prev = bars[index - 1]
                val range = bar.high - bar.low
                val amount = bar.turnover ?: bar.volume
                if (amount == null || amount == 0.0 || range == 0.0) 0.0 else {
                    val distance = (bar.high + bar.low) / 2.0 - (prev.high + prev.low) / 2.0
                    finiteOrNull(distance * range / amount)
                }
            }
        }
        val emv = nullableMovingAverage(daily, positivePeriod(resolved[0]))
        return indicatorResult(
            name,
            series,
            lineFigure("EMV", emv),
            lineFigure("MAEMV", nullableMovingAverage(emv, positivePeriod(resolved[1]))),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) return super.calculateIncremental(context, params)
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "EMV expects period and signal period" }
        val period = positivePeriod(resolved[0])
        val signalPeriod = positivePeriod(resolved[1])
        val start = context.change.newAffectedRange.first
        val changedEmv = (start until context.newBars.size).map { index ->
            val from = index - period + 1
            if (from < 0) null else {
                var sum = 0.0
                for (window in from..index) {
                    val daily = emvDaily(context.newBars, window) ?: return@map null
                    sum += daily
                }
                finiteOrNull(sum / period)
            }
        }
        val emv = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changedEmv, context)
        val changedSignal = (start until emv.size).map { index -> emv.finiteWindowAverage(index - signalPeriod + 1, index) }
        return spliceTailFigures(
            name,
            series,
            context,
            listOf(lineFigure("EMV", changedEmv), lineFigure("MAEMV", changedSignal)),
        )
    }
}

private fun emvDaily(bars: List<KLineBar>, index: Int): Double? {
    if (index == 0) return null
    val bar = bars[index]
    val prev = bars[index - 1]
    val range = bar.high - bar.low
    val amount = bar.turnover ?: bar.volume
    if (amount == null || amount == 0.0 || range == 0.0) return 0.0
    val distance = (bar.high + bar.low) / 2.0 - (prev.high + prev.low) / 2.0
    return finiteOrNull(distance * range / amount)
}

internal object KLineMtmIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "MTM expects period and signal period" }
        return resolved.sumOf(::positivePeriod)
    }
    override val name = "MTM"
    override val defaultParams = listOf(12.0, 6.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("MTM", "MTM", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MAMTM", "MAMTM", KLineIndicatorFigureType.LINE),
    )

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "MTM expects period and signal period" }
        val period = positivePeriod(resolved[0])
        val mtm = bars.indices.map { index ->
            if (index < period) null else finiteOrNull(bars[index].close - bars[index - period].close)
        }
        return indicatorResult(
            name,
            series,
            lineFigure("MTM", mtm),
            lineFigure("MAMTM", nullableMovingAverage(mtm, positivePeriod(resolved[1]))),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) return super.calculateIncremental(context, params)
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "MTM expects period and signal period" }
        val period = positivePeriod(resolved[0])
        val signalPeriod = positivePeriod(resolved[1])
        val start = context.change.newAffectedRange.first
        val changedMtm = (start until context.newBars.size).map { index ->
            if (index < period) null else finiteOrNull(context.newBars[index].close - context.newBars[index - period].close)
        }
        val mtm = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changedMtm, context)
        val changedSignal = (start until mtm.size).map { index -> mtm.finiteWindowAverage(index - signalPeriod + 1, index) }
        return spliceTailFigures(
            name,
            series,
            context,
            listOf(lineFigure("MTM", changedMtm), lineFigure("MAMTM", changedSignal)),
        )
    }
}

internal object KLinePsyIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "PSY expects period and signal period" }
        return resolved.sumOf(::positivePeriod)
    }
    override val name = "PSY"
    override val defaultParams = listOf(12.0, 6.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("PSY", "PSY", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MAPSY", "MAPSY", KLineIndicatorFigureType.LINE),
    )

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "PSY expects period and signal period" }
        val period = positivePeriod(resolved[0])
        val psy = MutableList<Double?>(bars.size) { null }
        val window = ArrayDeque<Boolean>(period)
        var up = 0
        var previousClose: Double? = null
        bars.forEachIndexed { index, bar ->
            val rose = bar.close > (previousClose ?: bar.close)
            if (rose) up++
            window.addLast(rose)
            if (window.size > period) {
                if (window.removeFirst()) up--
            }
            if (window.size == period) psy[index] = finiteOrNull(up * 100.0 / period)
            previousClose = bar.close
        }
        return indicatorResult(
            name,
            series,
            lineFigure("PSY", psy),
            lineFigure("MAPSY", nullableMovingAverage(psy, positivePeriod(resolved[1]))),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) return super.calculateIncremental(context, params)
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "PSY expects period and signal period" }
        val period = positivePeriod(resolved[0])
        val signalPeriod = positivePeriod(resolved[1])
        val start = context.change.newAffectedRange.first
        val changedPsy = (start until context.newBars.size).map { index -> psyValue(context.newBars, index, period) }
        val psy = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changedPsy, context)
        val changedSignal = (start until psy.size).map { index -> psy.finiteWindowAverage(index - signalPeriod + 1, index) }
        return spliceTailFigures(
            name,
            series,
            context,
            listOf(lineFigure("PSY", changedPsy), lineFigure("MAPSY", changedSignal)),
        )
    }
}

internal object KLineTrixIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "TRIX"
    override val defaultParams = listOf(12.0, 9.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(
        KLineIndicatorFigure("TRIX", "TRIX", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MATRIX", "MATRIX", KLineIndicatorFigureType.LINE),
    )

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val (period, signal) = trixParams(params)
        val alpha = 2.0 / (period + 1.0)
        var ema1: Double? = null
        var ema2: Double? = null
        var ema3: Double? = null
        var beforeEma1: Double? = null
        var beforeEma2: Double? = null
        var beforeEma3: Double? = null
        val trix = bars.map { bar ->
            beforeEma1 = ema1
            beforeEma2 = ema2
            beforeEma3 = ema3
            ema1 = finiteOrNull(ema1?.let { alpha * bar.close + (1.0 - alpha) * it } ?: bar.close)
            ema2 = ema1?.let { current -> finiteOrNull(ema2?.let { alpha * current + (1.0 - alpha) * it } ?: current) }
            ema3 = ema2?.let { current -> finiteOrNull(ema3?.let { alpha * current + (1.0 - alpha) * it } ?: current) }
            val previous = beforeEma3
            val current = ema3
            if (current == null || previous == null || previous == 0.0) null else finiteOrNull((current - previous) / previous * 100.0)
        }
        return KLineIndicatorResult(
            name,
            series,
            listOf(lineFigure("TRIX", trix), lineFigure("MATRIX", nullableMovingAverage(trix, signal))),
            TrixTailState(beforeEma1, beforeEma2, beforeEma3, ema1, ema2, ema3),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val (period, signal) = trixParams(params)
        val start = context.change.newAffectedRange.first
        val previous = context.previousResult.calculationState as? TrixTailState ?: return calculate(context.newBars, params)
        val alpha = 2.0 / (period + 1.0)
        var ema1 = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeEma1 else previous.ema1
        var ema2 = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeEma2 else previous.ema2
        var ema3 = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeEma3 else previous.ema3
        var beforeEma1 = ema1
        var beforeEma2 = ema2
        var beforeEma3 = ema3
        val changed = context.newBars.subList(start, context.newBars.size).map { bar ->
            beforeEma1 = ema1
            beforeEma2 = ema2
            beforeEma3 = ema3
            ema1 = finiteOrNull(ema1?.let { alpha * bar.close + (1.0 - alpha) * it } ?: bar.close)
            ema2 = ema1?.let { current -> finiteOrNull(ema2?.let { alpha * current + (1.0 - alpha) * it } ?: current) }
            ema3 = ema2?.let { current -> finiteOrNull(ema3?.let { alpha * current + (1.0 - alpha) * it } ?: current) }
            val prior = beforeEma3
            val current = ema3
            if (current == null || prior == null || prior == 0.0) null else finiteOrNull((current - prior) / prior * 100.0)
        }
        val trix = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changed, context)
        val changedMa = (start until trix.size).map { index ->
            val from = index - signal + 1
            if (from < 0) null else {
                val window = (from..index).map { trix[it] }
                if (window.any { it == null || it?.isFinite() != true }) null else finiteOrNull(window.filterNotNull().average())
            }
        }
        return KLineIndicatorResult(
            name,
            series,
            listOf(
                lineFigure("TRIX", trix),
                lineFigure(
                    "MATRIX",
                    context.previousResult.figures[1].values.persistentReplaceRange(start, context.oldBars.size, changedMa, context),
                ),
            ),
            TrixTailState(beforeEma1, beforeEma2, beforeEma3, ema1, ema2, ema3),
        )
    }

    private fun trixParams(params: List<Double>): Pair<Int, Int> {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "TRIX expects period and signal period" }
        return positivePeriod(resolved[0]) to positivePeriod(resolved[1])
    }
}

private data class TrixTailState(
    val beforeEma1: Double?,
    val beforeEma2: Double?,
    val beforeEma3: Double?,
    val ema1: Double?,
    val ema2: Double?,
    val ema3: Double?,
)

internal object KLineVrIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "VR expects period and signal period" }
        return resolved.sumOf(::positivePeriod) + 1
    }
    override val name = "VR"
    override val defaultParams = listOf(26.0, 6.0)
    override val series = KLineIndicatorSeries.VOLUME
    override val figures = listOf(
        KLineIndicatorFigure("VR", "VR", KLineIndicatorFigureType.LINE),
        KLineIndicatorFigure("MAVR", "MAVR", KLineIndicatorFigureType.LINE),
    )

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "VR expects period and signal period" }
        val period = positivePeriod(resolved[0])
        val vr = MutableList<Double?>(bars.size) { null }
        val window = ArrayDeque<VrPart>(period)
        var previousClose: Double? = null
        bars.forEachIndexed { index, bar ->
            val volume = bar.volume ?: 0.0
            val prev = previousClose ?: bar.close
            val part = when {
                bar.close > prev -> VrPart(volume, 0.0, 0.0)
                bar.close < prev -> VrPart(0.0, volume, 0.0)
                else -> VrPart(0.0, 0.0, volume)
            }
            window.addLast(part)
            if (window.size > period) window.removeFirst()
            if (window.size == period) {
                var sumUvs = 0.0
                var sumDvs = 0.0
                var sumPvs = 0.0
                window.forEach { item ->
                    sumUvs += item.uvs
                    sumDvs += item.dvs
                    sumPvs += item.pvs
                }
                val half = sumPvs / 2.0
                vr[index] = if (sumDvs + half == 0.0) 0.0 else finiteOrNull((sumUvs + half) / (sumDvs + half) * 100.0)
            }
            previousClose = bar.close
        }
        return indicatorResult(
            name,
            series,
            lineFigure("VR", vr),
            lineFigure("MAVR", nullableMovingAverage(vr, positivePeriod(resolved[1]))),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) return super.calculateIncremental(context, params)
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "VR expects period and signal period" }
        val period = positivePeriod(resolved[0])
        val signalPeriod = positivePeriod(resolved[1])
        val start = context.change.newAffectedRange.first
        val changedVr = (start until context.newBars.size).map { index -> vrValue(context.newBars, index, period) }
        val vr = context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changedVr, context)
        val changedSignal = (start until vr.size).map { index -> vr.finiteWindowAverage(index - signalPeriod + 1, index) }
        return spliceTailFigures(
            name,
            series,
            context,
            listOf(lineFigure("VR", changedVr), lineFigure("MAVR", changedSignal)),
        )
    }
}

internal object KLineAoIndicator : KLineIncrementalIndicatorTemplate {
    override fun finiteLookback(params: List<Double>): Int {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "AO expects short and long periods" }
        return resolved.maxOf(::positivePeriod)
    }
    override val name = "AO"
    override val defaultParams = listOf(5.0, 34.0)
    override val series = KLineIndicatorSeries.OSCILLATOR
    override val figures = listOf(KLineIndicatorFigure("AO", "AO", KLineIndicatorFigureType.BAR))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "AO expects short and long periods" }
        val median = bars.map { (it.high + it.low) / 2.0 }
        val short = movingAverage(median, positivePeriod(resolved[0]))
        val long = movingAverage(median, positivePeriod(resolved[1]))
        return indicatorResult(name, series, barFigure("AO", combine(short, long) { left, right -> left - right }))
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) return super.calculateIncremental(context, params)
        val resolved = params.ifEmpty { defaultParams }
        require(resolved.size == 2) { "AO expects short and long periods" }
        val shortPeriod = positivePeriod(resolved[0])
        val longPeriod = positivePeriod(resolved[1])
        val start = context.change.newAffectedRange.first
        val changed = (start until context.newBars.size).map { index ->
            val short = context.newBars.medianAverage(index - shortPeriod + 1, index) ?: return@map null
            val long = context.newBars.medianAverage(index - longPeriod + 1, index) ?: return@map null
            finiteOrNull(short - long)
        }
        return spliceTailFigures(name, series, context, listOf(barFigure("AO", changed)))
    }
}

internal object KLinePvtIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "PVT"
    override val defaultParams = emptyList<Double>()
    override val series = KLineIndicatorSeries.VOLUME
    override val figures = listOf(KLineIndicatorFigure("PVT", "PVT", KLineIndicatorFigureType.LINE))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        require(params.isEmpty()) { "PVT does not accept params" }
        var sum = 0.0
        val values = bars.indices.map { index ->
            val prev = bars.getOrNull(index - 1)?.close ?: bars[index].close
            val volume = bars[index].volume ?: 0.0
            if (prev != 0.0) sum += (bars[index].close - prev) / prev * volume
            finiteOrNull(sum)
        }
        return indicatorResult(name, series, lineFigure("PVT", values))
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val start = context.change.newAffectedRange.first
        var sum = if (start == 0) 0.0 else context.previousResult.figures[0].values.getOrNull(start - 1) ?: 0.0
        val changed = (start until context.newBars.size).map { index ->
            val prev = context.newBars.getOrNull(index - 1)?.close ?: context.newBars[index].close
            val volume = context.newBars[index].volume ?: 0.0
            if (prev != 0.0) sum += (context.newBars[index].close - prev) / prev * volume
            finiteOrNull(sum)
        }
        return indicatorResult(
            name,
            series,
            lineFigure(
                "PVT",
                context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changed, context),
            ),
        )
    }
}

internal object KLineAvpIndicator : KLineIncrementalIndicatorTemplate {
    override val name = "AVP"
    override val defaultParams = emptyList<Double>()
    override val series = KLineIndicatorSeries.PRICE
    override val figures = listOf(KLineIndicatorFigure("AVP", "AVP", KLineIndicatorFigureType.LINE))

    override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
        require(params.isEmpty()) { "AVP does not accept params" }
        var turnover = 0.0
        var volume = 0.0
        var beforeTurnover = 0.0
        var beforeVolume = 0.0
        val values = bars.map { bar ->
            beforeTurnover = turnover
            beforeVolume = volume
            turnover += bar.turnover ?: 0.0
            volume += bar.volume ?: 0.0
            if (volume == 0.0) null else finiteOrNull(turnover / volume)
        }
        return KLineIndicatorResult(
            name,
            series,
            listOf(lineFigure("AVP", values)),
            AvpTailState(beforeTurnover, beforeVolume, turnover, volume),
        )
    }

    override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult {
        if (!context.change.kind.isTail) {
            context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val previous = context.previousResult.calculationState as? AvpTailState ?: return calculate(context.newBars, params)
        val start = context.change.newAffectedRange.first
        var turnover = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeTurnover else previous.turnover
        var volume = if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) previous.beforeVolume else previous.volume
        var beforeTurnover = turnover
        var beforeVolume = volume
        val changed = context.newBars.subList(start, context.newBars.size).map { bar ->
            beforeTurnover = turnover
            beforeVolume = volume
            turnover += bar.turnover ?: 0.0
            volume += bar.volume ?: 0.0
            if (volume == 0.0) null else finiteOrNull(turnover / volume)
        }
        return KLineIndicatorResult(
            name,
            series,
            listOf(
                lineFigure(
                    "AVP",
                    context.previousResult.figures[0].values.persistentReplaceRange(start, context.oldBars.size, changed, context),
                ),
            ),
            AvpTailState(beforeTurnover, beforeVolume, turnover, volume),
        )
    }
}

private data class AvpTailState(
    val beforeTurnover: Double,
    val beforeVolume: Double,
    val turnover: Double,
    val volume: Double,
)

private data class BrarPart(val ho: Double, val ol: Double, val hcy: Double, val cyl: Double)

private data class VrPart(val uvs: Double, val dvs: Double, val pvs: Double)

private fun spliceTailFigures(
    name: String,
    series: KLineIndicatorSeries,
    context: KLineIndicatorUpdateContext,
    changed: List<KLineIndicatorFigureResult>,
    state: Any? = null,
): KLineIndicatorResult {
    val start = context.change.newAffectedRange.first
    return KLineIndicatorResult(
        name,
        series,
        changed.zip(context.previousResult.figures) { figure, old ->
            KLineIndicatorFigureResult(
                figure.key,
                figure.type,
                old.values.persistentReplaceRange(start, context.oldBars.size, figure.values, context),
            )
        },
        state,
    )
}

private fun List<Double?>.finiteWindowAverage(from: Int, to: Int): Double? {
    if (from < 0) return null
    var sum = 0.0
    for (index in from..to) {
        val value = this[index] ?: return null
        if (!value.isFinite()) return null
        sum += value
    }
    return finiteOrNull(sum / (to - from + 1))
}

private fun List<KLineBar>.closeAverage(from: Int, to: Int): Double? {
    if (from < 0) return null
    var sum = 0.0
    for (index in from..to) sum += this[index].close
    return finiteOrNull(sum / (to - from + 1))
}

private fun List<KLineBar>.medianAverage(from: Int, to: Int): Double? {
    if (from < 0) return null
    var sum = 0.0
    for (index in from..to) {
        val bar = this[index]
        sum += (bar.high + bar.low) / 2.0
    }
    return finiteOrNull(sum / (to - from + 1))
}

private fun psyValue(bars: List<KLineBar>, index: Int, period: Int): Double? {
    if (index + 1 < period) return null
    var up = 0
    for (window in index - period + 1..index) {
        val prev = bars.getOrNull(window - 1)?.close ?: bars[window].close
        if (bars[window].close > prev) up++
    }
    return finiteOrNull(up * 100.0 / period)
}

private fun vrValue(bars: List<KLineBar>, index: Int, period: Int): Double? {
    if (index + 1 < period) return null
    var uvs = 0.0
    var dvs = 0.0
    var pvs = 0.0
    for (window in index - period + 1..index) {
        val volume = bars[window].volume ?: 0.0
        val prev = bars.getOrNull(window - 1)?.close ?: bars[window].close
        when {
            bars[window].close > prev -> uvs += volume
            bars[window].close < prev -> dvs += volume
            else -> pvs += volume
        }
    }
    val half = pvs / 2.0
    return if (dvs + half == 0.0) 0.0 else finiteOrNull((uvs + half) / (dvs + half) * 100.0)
}
