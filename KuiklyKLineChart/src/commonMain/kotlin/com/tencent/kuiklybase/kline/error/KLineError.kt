package com.tencent.kuiklybase.kline.error

enum class KLineErrorCode {
    INVALID_DATA,
    INITIAL_LOAD_FAILED,
    HISTORY_LOAD_FAILED,
    REALTIME_SUBSCRIPTION_FAILED,
    INDICATOR_CALCULATION_FAILED,
    STATE_RESTORE_FAILED,
}

data class KLineError(
    val code: KLineErrorCode,
    val message: String,
    val timestamp: Long? = null,
    val instanceId: String? = null,
    val templateName: String? = null,
)
