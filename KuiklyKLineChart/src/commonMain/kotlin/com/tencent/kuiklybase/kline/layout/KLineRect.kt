package com.tencent.kuiklybase.kline.layout

data class KLineRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    init {
        require(listOf(left, top, right, bottom).all(Double::isFinite)) {
            "Rectangle coordinates must be finite"
        }
        require(right >= left) { "Rectangle right must not be less than left" }
        require(bottom >= top) { "Rectangle bottom must not be less than top" }
    }
}

