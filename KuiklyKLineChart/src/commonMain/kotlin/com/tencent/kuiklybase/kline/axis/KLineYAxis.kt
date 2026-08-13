package com.tencent.kuiklybase.kline.axis

enum class KLineYAxisMode {
    NORMAL,
    PERCENTAGE,
    LOGARITHMIC,
}

data class KLineYAxis(
    val id: String,
    val minValue: Double,
    val maxValue: Double,
    val mode: KLineYAxisMode = KLineYAxisMode.NORMAL,
    val referenceValue: Double? = null,
    val autoScale: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Y axis id must not be blank" }
        require(minValue.isFinite() && maxValue.isFinite() && maxValue > minValue) {
            "Y axis range must be finite and increasing"
        }
        if (mode == KLineYAxisMode.PERCENTAGE) {
            require(referenceValue != null && referenceValue.isFinite() && referenceValue > 0.0) {
                "Percentage axis requires a finite positive reference value"
            }
        }
        if (mode == KLineYAxisMode.LOGARITHMIC) {
            require(minValue > 0.0) { "Logarithmic axis values must be positive" }
        }
    }
}
