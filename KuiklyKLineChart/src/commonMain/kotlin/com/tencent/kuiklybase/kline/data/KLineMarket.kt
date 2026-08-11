package com.tencent.kuiklybase.kline.data

data class KLineSymbol(
    val ticker: String,
    val name: String = ticker,
    val pricePrecision: Int = 2,
    val volumePrecision: Int = 0,
    val timezone: String = "Asia/Shanghai",
) {
    init {
        require(ticker.isNotBlank()) { "Ticker must not be blank" }
        require(pricePrecision in 0..12) { "Price precision must be between 0 and 12" }
        require(volumePrecision in 0..12) { "Volume precision must be between 0 and 12" }
        require(timezone.isNotBlank()) { "Timezone must not be blank" }
    }
}

data class KLinePeriod(
    val span: Int,
    val unit: KLinePeriodUnit,
) {
    init {
        require(span > 0) { "Period span must be positive" }
    }
}

enum class KLinePeriodUnit {
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
}
