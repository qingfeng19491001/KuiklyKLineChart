package com.tencent.kuiklybase.kline

enum class KLinePriceStyle {
    CANDLE,
    CANDLE_HOLLOW,
    CANDLE_UP_STROKE,
    CANDLE_DOWN_STROKE,
    OHLC,
    LINE,
    AREA,
    ;

    companion object {
        fun parse(name: String): KLinePriceStyle = when (
            name.trim().lowercase().replace('-', '_')
        ) {
            "line" -> LINE
            "area" -> AREA
            "ohlc" -> OHLC
            "candle_hollow", "candle_stroke", "hollow" -> CANDLE_HOLLOW
            "candle_up_stroke", "up_stroke" -> CANDLE_UP_STROKE
            "candle_down_stroke", "down_stroke" -> CANDLE_DOWN_STROKE
            else -> CANDLE
        }
    }
}

/** Immutable rendering/interaction presets sharing the same planner and pipeline. */
enum class KLineChartMode(
    val axisLabels: Boolean,
    val secondaryPanes: Boolean,
    val indicators: Boolean,
    val overlays: Boolean,
    val crosshair: Boolean,
    val tooltip: Boolean,
    val interaction: Boolean,
    val maxVisibleBars: Int,
    val overscanBars: Int,
) {
    FULL(true, true, true, true, true, true, true, Int.MAX_VALUE, 1),
    COMPACT(false, false, false, false, false, false, false, 60, 0),
}
