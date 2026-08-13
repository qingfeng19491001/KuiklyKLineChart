package com.tencent.kuiklybase.kline

enum class KLinePriceStyle { CANDLE, LINE }

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
