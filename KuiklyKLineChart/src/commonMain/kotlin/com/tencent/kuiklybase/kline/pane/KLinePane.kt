package com.tencent.kuiklybase.kline.pane

import com.tencent.kuiklybase.kline.axis.KLineYAxis

enum class KLinePaneKind {
    PRICE,
    INDICATOR,
}

enum class KLinePaneState {
    NORMAL,
    MINIMIZED,
    MAXIMIZED,
}

data class KLinePane(
    val id: String,
    val kind: KLinePaneKind,
    val order: Int,
    val weight: Double,
    val minHeight: Double,
    val state: KLinePaneState = KLinePaneState.NORMAL,
    val yAxes: List<KLineYAxis>,
) {
    init {
        require(id.isNotBlank()) { "Pane id must not be blank" }
        require(weight.isFinite() && weight > 0.0) { "Pane weight must be finite and positive" }
        require(minHeight.isFinite() && minHeight >= 0.0) {
            "Pane minimum height must be finite and non-negative"
        }
        require(yAxes.isNotEmpty()) { "Pane must contain at least one Y axis" }
        require(yAxes.map(KLineYAxis::id).distinct().size == yAxes.size) {
            "Pane Y axis ids must be unique"
        }
    }
}

