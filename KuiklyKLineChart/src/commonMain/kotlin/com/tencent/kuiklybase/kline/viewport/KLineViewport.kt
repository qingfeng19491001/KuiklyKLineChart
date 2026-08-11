package com.tencent.kuiklybase.kline.viewport

data class KLineViewport(
    val startIndex: Double,
    val endIndex: Double,
    val barSpace: Double,
    val rightOffset: Double,
    val zoomAnchor: Double? = null,
) {
    init {
        require(startIndex.isFinite() && endIndex.isFinite()) { "Viewport indices must be finite" }
        require(endIndex >= startIndex) { "Viewport end must not be less than start" }
        require(barSpace.isFinite() && barSpace > 0.0) { "Bar space must be finite and positive" }
        require(rightOffset.isFinite()) { "Right offset must be finite" }
        require(zoomAnchor == null || zoomAnchor.isFinite()) { "Zoom anchor must be finite" }
    }
}

data class KLineViewportConfig(
    val defaultBarSpace: Double = 10.0,
    val minBarSpace: Double = 2.0,
    val maxBarSpace: Double = 40.0,
    val maxRightOffsetBars: Double = 20.0,
) {
    init {
        require(defaultBarSpace.isFinite() && defaultBarSpace > 0.0) {
            "Default bar space must be finite and positive"
        }
        require(minBarSpace.isFinite() && minBarSpace > 0.0) {
            "Minimum bar space must be finite and positive"
        }
        require(maxBarSpace.isFinite() && maxBarSpace >= minBarSpace) {
            "Maximum bar space must be finite and not less than minimum"
        }
        require(defaultBarSpace in minBarSpace..maxBarSpace) {
            "Default bar space must be within the configured range"
        }
        require(maxRightOffsetBars.isFinite() && maxRightOffsetBars >= 0.0) {
            "Maximum right offset must be finite and non-negative"
        }
    }
}

