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
    val values: List<Double?> = ImmutableSnapshotList(values)

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

class KLineIndicatorResult(
    val templateName: String,
    val series: KLineIndicatorSeries,
    figures: List<KLineIndicatorFigureResult>,
) {
    val figures: List<KLineIndicatorFigureResult> = ImmutableSnapshotList(figures)

    fun copy(
        templateName: String = this.templateName,
        series: KLineIndicatorSeries = this.series,
        figures: List<KLineIndicatorFigureResult> = this.figures,
    ) = KLineIndicatorResult(templateName, series, figures)

    override fun equals(other: Any?) = other is KLineIndicatorResult &&
        templateName == other.templateName && series == other.series && figures == other.figures
    override fun hashCode() = 31 * (31 * templateName.hashCode() + series.hashCode()) + figures.hashCode()
    override fun toString() = "KLineIndicatorResult(templateName=$templateName, series=$series, figures=$figures)"
}

interface KLineIndicatorTemplate {
    val name: String
    val defaultParams: List<Double>
    val series: KLineIndicatorSeries
    val figures: List<KLineIndicatorFigure>

    fun calculate(
        bars: List<KLineBar>,
        params: List<Double> = defaultParams,
    ): KLineIndicatorResult
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
