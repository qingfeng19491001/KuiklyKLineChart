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

data class KLineIndicatorFigureResult(
    val key: String,
    val type: KLineIndicatorFigureType,
    val values: List<Double?>,
)

data class KLineIndicatorResult(
    val templateName: String,
    val series: KLineIndicatorSeries,
    val figures: List<KLineIndicatorFigureResult>,
)

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

data class KLineIndicatorInstance(
    val id: String,
    val templateName: String,
    val paneId: String,
    val params: List<Double>,
    val precision: Int,
    val visible: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Indicator instance id must not be blank" }
        require(templateName.isNotBlank()) { "Indicator template name must not be blank" }
        require(paneId.isNotBlank()) { "Indicator pane id must not be blank" }
        require(params.all(Double::isFinite)) { "Indicator params must be finite" }
        require(precision >= 0) { "Indicator precision must not be negative" }
    }
}
