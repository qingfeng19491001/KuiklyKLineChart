package com.tencent.kuiklybase.kline.data

data class KLineBar(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double? = null,
    val turnover: Double? = null,
)

