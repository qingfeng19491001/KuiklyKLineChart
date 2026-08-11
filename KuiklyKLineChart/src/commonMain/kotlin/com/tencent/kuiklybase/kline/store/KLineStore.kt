package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineBarValidator
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode

data class KLineStoreSnapshot(
    val symbol: KLineSymbol? = null,
    val period: KLinePeriod? = null,
    val bars: List<KLineBar> = emptyList(),
    val hasMoreBefore: Boolean = false,
    val hasMoreAfter: Boolean = false,
    val loadState: KLineLoadState = KLineLoadState(),
    val dataRevision: Long = 0,
)

enum class KLineLoadPhase {
    IDLE,
    LOADING,
    FAILED,
}

data class KLineLoadState(
    val initial: KLineLoadPhase = KLineLoadPhase.IDLE,
    val before: KLineLoadPhase = KLineLoadPhase.IDLE,
    val after: KLineLoadPhase = KLineLoadPhase.IDLE,
)

class KLineStore(
    private val onError: (KLineError) -> Unit = {},
) {
    var snapshot: KLineStoreSnapshot = KLineStoreSnapshot()
        private set

    internal fun reset(
        symbol: KLineSymbol,
        period: KLinePeriod,
    ) {
        snapshot = KLineStoreSnapshot(
            symbol = symbol,
            period = period,
            dataRevision = snapshot.dataRevision + 1,
        )
    }

    internal fun setLoadPhase(
        direction: KLineLoadDirection,
        phase: KLineLoadPhase,
    ) {
        val state = snapshot.loadState
        snapshot = snapshot.copy(
            loadState = when (direction) {
                KLineLoadDirection.INITIAL -> state.copy(initial = phase)
                KLineLoadDirection.BEFORE -> state.copy(before = phase)
                KLineLoadDirection.AFTER -> state.copy(after = phase)
            },
        )
    }

    internal fun onLoadFailure(
        direction: KLineLoadDirection,
        message: String,
    ) {
        setLoadPhase(direction, KLineLoadPhase.FAILED)
        onError(
            KLineError(
                code = if (direction == KLineLoadDirection.INITIAL) {
                    KLineErrorCode.INITIAL_LOAD_FAILED
                } else {
                    KLineErrorCode.HISTORY_LOAD_FAILED
                },
                message = message,
            ),
        )
    }

    internal fun onRealtimeFailure(message: String) {
        onError(
            KLineError(
                code = KLineErrorCode.REALTIME_SUBSCRIPTION_FAILED,
                message = message,
            ),
        )
    }

    fun replaceAll(
        bars: List<KLineBar>,
        hasMoreBefore: Boolean = false,
        hasMoreAfter: Boolean = false,
    ) {
        val normalized = normalize(bars)
        snapshot = snapshot.copy(
            bars = normalized,
            hasMoreBefore = hasMoreBefore,
            hasMoreAfter = hasMoreAfter,
            dataRevision = snapshot.dataRevision + 1,
        )
    }

    fun prepend(
        bars: List<KLineBar>,
        hasMoreBefore: Boolean,
    ): Int {
        val normalized = normalize(bars)
        val previousFirstTimestamp = snapshot.bars.firstOrNull()?.timestamp
        val existingTimestamps = snapshot.bars.mapTo(mutableSetOf(), KLineBar::timestamp)
        val insertedBefore = normalized.count { bar ->
            bar.timestamp !in existingTimestamps &&
                (previousFirstTimestamp == null || bar.timestamp < previousFirstTimestamp)
        }
        snapshot = snapshot.copy(
            bars = merge(normalized, snapshot.bars),
            hasMoreBefore = hasMoreBefore,
            dataRevision = snapshot.dataRevision + 1,
        )
        return insertedBefore
    }

    fun append(
        bars: List<KLineBar>,
        hasMoreAfter: Boolean,
    ) {
        snapshot = snapshot.copy(
            bars = merge(snapshot.bars, normalize(bars)),
            hasMoreAfter = hasMoreAfter,
            dataRevision = snapshot.dataRevision + 1,
        )
    }

    fun applyRealtime(bar: KLineBar) {
        if (!accept(bar)) return
        val tail = snapshot.bars.lastOrNull()
        val updatedBars = when {
            tail == null -> listOf(bar)
            bar.timestamp == tail.timestamp -> snapshot.bars.dropLast(1) + bar
            bar.timestamp > tail.timestamp -> snapshot.bars + bar
            else -> return
        }
        snapshot = snapshot.copy(
            bars = updatedBars,
            dataRevision = snapshot.dataRevision + 1,
        )
    }

    private fun accept(bar: KLineBar): Boolean {
        val reason = KLineBarValidator.invalidReason(bar) ?: return true
        onError(
            KLineError(
                code = KLineErrorCode.INVALID_DATA,
                message = reason,
                timestamp = bar.timestamp,
            ),
        )
        return false
    }

    private fun normalize(bars: List<KLineBar>): List<KLineBar> = bars
        .filter(::accept)
        .associateBy(KLineBar::timestamp)
        .values
        .sortedBy(KLineBar::timestamp)

    private fun merge(
        first: List<KLineBar>,
        second: List<KLineBar>,
    ): List<KLineBar> = (first + second)
        .associateBy(KLineBar::timestamp)
        .values
        .sortedBy(KLineBar::timestamp)
}
