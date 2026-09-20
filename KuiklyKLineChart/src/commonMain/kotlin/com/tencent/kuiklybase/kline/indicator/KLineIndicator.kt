package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar

enum class KLineIndicatorSeries {
    PRICE,
    VOLUME,
    OSCILLATOR,
}

enum class KLineIndicatorFigureType {
    LINE,
    BAR,
    AREA,
    BAND,
    POINT,
    CANDLE,
}

data class KLineIndicatorFigure(
    val key: String,
    val title: String,
    val type: KLineIndicatorFigureType,
)

class KLineIndicatorFigureResult(
    val key: String,
    val type: KLineIndicatorFigureType,
    values: List<Double?>,
) {
    val values: List<Double?> = PersistentIndicatorList.from(values)

    fun copy(
        key: String = this.key,
        type: KLineIndicatorFigureType = this.type,
        values: List<Double?> = this.values,
    ) = KLineIndicatorFigureResult(key, type, values)

    override fun equals(other: Any?) = other is KLineIndicatorFigureResult &&
        key == other.key && type == other.type && values == other.values
    override fun hashCode() = 31 * (31 * key.hashCode() + type.hashCode()) + values.hashCode()
    override fun toString() = "KLineIndicatorFigureResult(key=$key, type=$type, values=$values)"
}

class KLineIndicatorResult internal constructor(
    val templateName: String,
    val series: KLineIndicatorSeries,
    figures: List<KLineIndicatorFigureResult>,
    internal val calculationState: Any?,
) {
    val figures: List<KLineIndicatorFigureResult> = ImmutableSnapshotList(figures)

    constructor(
        templateName: String,
        series: KLineIndicatorSeries,
        figures: List<KLineIndicatorFigureResult>,
    ) : this(templateName, series, figures, null)

    fun copy(
        templateName: String = this.templateName,
        series: KLineIndicatorSeries = this.series,
        figures: List<KLineIndicatorFigureResult> = this.figures,
    ) = KLineIndicatorResult(templateName, series, figures, calculationState)

    override fun equals(other: Any?) = this === other || other is KLineIndicatorResult &&
        templateName == other.templateName && series == other.series && figures == other.figures
    override fun hashCode() = 31 * (31 * templateName.hashCode() + series.hashCode()) + figures.hashCode()
    override fun toString() = "KLineIndicatorResult(templateName=$templateName, series=$series, figures=$figures)"

    internal fun hasEquivalentCalculationState(other: KLineIndicatorResult): Boolean =
        calculationState == other.calculationState
}

interface KLineIndicatorTemplate {
    val name: String
    val defaultParams: List<Double>
    val series: KLineIndicatorSeries
    val figures: List<KLineIndicatorFigure>

    fun figureSchema(params: List<Double> = defaultParams): List<KLineIndicatorFigure> = figures

    fun calculate(
        bars: List<KLineBar>,
        params: List<Double> = defaultParams,
    ): KLineIndicatorResult

    /** Returns null to request the safe full-calculation fallback. */
    fun calculateIncremental(
        context: KLineIndicatorUpdateContext,
        params: List<Double> = defaultParams,
    ): KLineIndicatorResult? = null

    /**
     * Conservative superset of output indices that may differ after [calculateIncremental].
     * Returning null requests a full result comparison. The engine also falls back to a full
     * comparison for empty or out-of-bounds ranges.
     */
    fun outputAffectedRange(context: KLineIndicatorUpdateContext): IntRange? = null

    /** Build a concrete [KLineIndicatorInstance] tied to a pane, with optional param override. */
    fun instance(
        id: String,
        paneId: String,
        params: List<Double> = this.defaultParams,
        precision: Int = 2,
        visible: Boolean = true,
    ): KLineIndicatorInstance = KLineIndicatorInstance(
        id = id,
        templateName = this.name,
        paneId = paneId,
        params = params.ifEmpty { this.defaultParams },
        precision = precision,
        visible = visible,
    )
}

/** Shared affected-window reuse for built-ins with finite history. */
interface KLineIncrementalIndicatorTemplate : KLineIndicatorTemplate {
    /** Null means the formula has unbounded history (for example an EMA). */
    fun finiteLookback(params: List<Double>): Int? = null

    override fun outputAffectedRange(context: KLineIndicatorUpdateContext): IntRange? =
        if (isTrustedBuiltInIncrementalTemplate(this) && context.change.kind.isTail) {
            context.change.newAffectedRange
        } else {
            null
        }

    override fun calculateIncremental(
        context: KLineIndicatorUpdateContext,
        params: List<Double>,
    ): KLineIndicatorResult {
        val lookback = finiteLookback(params)
        if (lookback != null && context.change.kind.isTail) {
            val affectedFrom = context.change.newAffectedRange.first
            val windowFrom = (affectedFrom - lookback + 1).coerceAtLeast(0)
            val localAffectedFrom = affectedFrom - windowFrom
            val suffix = calculate(context.newBars.subList(windowFrom, context.newBars.size), params)
            return KLineIndicatorResult(
                name,
                series,
                suffix.figures.zip(context.previousResult.figures).map { (newFigure, oldFigure) ->
                    KLineIndicatorFigureResult(
                        newFigure.key,
                        newFigure.type,
                        oldFigure.values.persistentReplaceRange(
                            context.change.oldAffectedRange.first,
                            context.oldBars.size - context.change.unchangedSuffixCount,
                            newFigure.values.persistentSlice(localAffectedFrom, newFigure.values.size, context),
                            context,
                        ),
                    )
                },
            )
        }
        if (context.change.kind != KLineDataChangeKind.HEAD_PREPEND || lookback == null) {
            if (lookback == null) context.recordFullCalculation()
            return calculate(context.newBars, params)
        }
        val inserted = context.newBars.size - context.oldBars.size
        val calculatedCount = minOf(context.newBars.size, inserted + lookback - 1)
        val prefix = calculate(context.newBars.take(calculatedCount), params)
        val reusedFrom = (lookback - 1).coerceAtMost(context.oldBars.size)
        return KLineIndicatorResult(
            name,
            series,
            prefix.figures.zip(context.previousResult.figures).map { (newFigure, oldFigure) ->
                KLineIndicatorFigureResult(
                    newFigure.key,
                    newFigure.type,
                    newFigure.values.persistentConcat(
                        oldFigure.values.persistentSlice(reusedFrom, oldFigure.values.size, context),
                        context,
                    ),
                )
            },
        )
    }
}

private val trustedBuiltInIncrementalTemplates: List<KLineIncrementalIndicatorTemplate> by lazy {
    listOf(
        KLineMovingAverageIndicator,
        KLineBollIndicator,
        KLineExpmaIndicator,
        KLineBbiIndicator,
        KLineEneIndicator,
        KLineVolumeIndicator,
        KLineAmountIndicator,
        KLineMacdIndicator,
        KLineKdjIndicator,
        KLineRsiIndicator,
        KLineWrIndicator,
        KLineBbdIndicator,
        KLineSmaIndicator,
        KLineEmaIndicator,
        KLineSarIndicator,
        KLineObvIndicator,
        KLineCciIndicator,
        KLineDmiIndicator,
        KLineBiasIndicator,
        KLineRocIndicator,
        KLineBrarIndicator,
        KLineCrIndicator,
        KLineDmaIndicator,
        KLineEmvIndicator,
        KLineMtmIndicator,
        KLinePsyIndicator,
        KLineTrixIndicator,
        KLineVrIndicator,
        KLineAoIndicator,
        KLinePvtIndicator,
        KLineAvpIndicator,
    )
}

private fun isTrustedBuiltInIncrementalTemplate(template: KLineIncrementalIndicatorTemplate): Boolean =
    trustedBuiltInIncrementalTemplates.any { it === template }

enum class KLineDataChangeKind {
    FULL_REPLACE,
    TAIL_APPEND,
    TAIL_UPDATE,
    HEAD_PREPEND,
    RANGE_UPDATE,
}

internal val KLineDataChangeKind.isTail: Boolean
    get() = this == KLineDataChangeKind.TAIL_APPEND || this == KLineDataChangeKind.TAIL_UPDATE

data class KLineDataChange(
    val kind: KLineDataChangeKind,
    val oldAffectedRange: IntRange,
    val newAffectedRange: IntRange,
    val unchangedPrefixCount: Int,
    val unchangedSuffixCount: Int,
)

class KLineIndicatorUpdateContext internal constructor(
    val oldBars: List<KLineBar>,
    val newBars: List<KLineBar>,
    val previousResult: KLineIndicatorResult,
    val change: KLineDataChange,
    internal val performanceTracker: KLinePerformanceTracker?,
    internal val performanceKey: KLinePerformanceKey?,
) {
    constructor(
        oldBars: List<KLineBar>,
        newBars: List<KLineBar>,
        previousResult: KLineIndicatorResult,
        change: KLineDataChange,
    ) : this(oldBars, newBars, previousResult, change, null, null)

    internal fun recordFullCalculation() {
        val tracker = performanceTracker ?: return
        tracker.recordFullCalculation(performanceKey ?: return)
    }


    fun copy(
        oldBars: List<KLineBar> = this.oldBars,
        newBars: List<KLineBar> = this.newBars,
        previousResult: KLineIndicatorResult = this.previousResult,
        change: KLineDataChange = this.change,
    ) = KLineIndicatorUpdateContext(oldBars, newBars, previousResult, change)

    operator fun component1() = oldBars
    operator fun component2() = newBars
    operator fun component3() = previousResult
    operator fun component4() = change

    override fun equals(other: Any?) = other is KLineIndicatorUpdateContext &&
        oldBars == other.oldBars && newBars == other.newBars && previousResult == other.previousResult && change == other.change

    override fun hashCode(): Int {
        var result = oldBars.hashCode()
        result = 31 * result + newBars.hashCode()
        result = 31 * result + previousResult.hashCode()
        return 31 * result + change.hashCode()
    }

    override fun toString() = "KLineIndicatorUpdateContext(oldBars=$oldBars, newBars=$newBars, previousResult=$previousResult, change=$change)"
}

class KLineIndicatorInstance(
    val id: String,
    val templateName: String,
    val paneId: String,
    params: List<Double>,
    val precision: Int,
    val visible: Boolean = true,
) {
    val params: List<Double> = params.toList()
    init {
        require(id.isNotBlank()) { "Indicator instance id must not be blank" }
        require(templateName.isNotBlank()) { "Indicator template name must not be blank" }
        require(paneId.isNotBlank()) { "Indicator pane id must not be blank" }
        require(params.all(Double::isFinite)) { "Indicator params must be finite" }
        require(precision in 0..12) { "Indicator precision must be between 0 and 12" }
    }

    fun copy(
        id: String = this.id,
        templateName: String = this.templateName,
        paneId: String = this.paneId,
        params: List<Double> = this.params,
        precision: Int = this.precision,
        visible: Boolean = this.visible,
    ) = KLineIndicatorInstance(id, templateName, paneId, params, precision, visible)

    override fun equals(other: Any?) = other is KLineIndicatorInstance && id == other.id &&
        templateName == other.templateName && paneId == other.paneId && params == other.params &&
        precision == other.precision && visible == other.visible
    override fun hashCode(): Int {
        var result = id.hashCode(); result = 31 * result + templateName.hashCode(); result = 31 * result + paneId.hashCode()
        result = 31 * result + params.hashCode(); result = 31 * result + precision; return 31 * result + visible.hashCode()
    }
    override fun toString() = "KLineIndicatorInstance(id=$id, templateName=$templateName, paneId=$paneId, params=$params, precision=$precision, visible=$visible)"
}

private class ImmutableSnapshotList<T>(source: List<T>) : AbstractList<T>() {
    private val values = source.toList()
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}
