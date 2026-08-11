package com.tencent.kuiklybase.kline.data

internal object KLineBarValidator {
    fun invalidReason(bar: KLineBar): String? {
        val prices = listOf(bar.open, bar.high, bar.low, bar.close)
        if (prices.any { !it.isFinite() }) {
            return "OHLC values must be finite"
        }
        if (bar.low > bar.high || bar.open !in bar.low..bar.high || bar.close !in bar.low..bar.high) {
            return "OHLC values must satisfy low <= open/close <= high"
        }
        if (bar.volume != null && (!bar.volume.isFinite() || bar.volume < 0.0)) {
            return "Volume must be finite and non-negative"
        }
        if (bar.turnover != null && (!bar.turnover.isFinite() || bar.turnover < 0.0)) {
            return "Turnover must be finite and non-negative"
        }
        return null
    }
}

