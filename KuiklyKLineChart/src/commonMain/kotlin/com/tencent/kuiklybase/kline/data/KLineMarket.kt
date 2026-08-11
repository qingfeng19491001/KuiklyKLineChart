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
        require(pricePrecision >= 0) { "Price precision must be non-negative" }
        require(volumePrecision >= 0) { "Volume precision must be non-negative" }
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

