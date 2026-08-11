package com.tencent.kuiklybase.kline.data

interface KLineDataSource {
    fun load(
        request: KLineLoadRequest,
        callback: KLineLoadCallback,
    ): KLineCancelable

    fun subscribe(
        symbol: KLineSymbol,
        period: KLinePeriod,
        listener: KLineRealtimeListener,
    ): KLineCancelable
}

enum class KLineLoadDirection {
    INITIAL,
    BEFORE,
    AFTER,
}

data class KLineLoadRequest(
    val symbol: KLineSymbol,
    val period: KLinePeriod,
    val direction: KLineLoadDirection,
    val anchorTimestamp: Long? = null,
    val limit: Int,
) {
    init {
        require(limit > 0) { "Load limit must be positive" }
        require(direction == KLineLoadDirection.INITIAL || anchorTimestamp != null) {
            "Historical loads require an anchor timestamp"
        }
    }
}

data class KLineLoadPage(
    val bars: List<KLineBar>,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
)

sealed interface KLineLoadResult {
    data class Success(val page: KLineLoadPage) : KLineLoadResult

    data class Failure(val message: String) : KLineLoadResult
}

fun interface KLineLoadCallback {
    fun onResult(result: KLineLoadResult)
}

sealed interface KLineRealtimeEvent {
    data class Bar(val bar: KLineBar) : KLineRealtimeEvent

    data class Failure(val message: String) : KLineRealtimeEvent
}

fun interface KLineRealtimeListener {
    fun onEvent(event: KLineRealtimeEvent)
}

fun interface KLineCancelable {
    fun cancel()
}

