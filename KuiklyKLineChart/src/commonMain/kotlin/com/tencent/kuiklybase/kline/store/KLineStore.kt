package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineBarValidator
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.viewport.KLineViewport

data class KLineStoreSnapshot(
    val symbol: KLineSymbol? = null,
    val period: KLinePeriod? = null,
    val bars: List<KLineBar> = emptyList(),
    val hasMoreBefore: Boolean = false,
    val hasMoreAfter: Boolean = false,
    val loadState: KLineLoadState = KLineLoadState(),
    val viewport: KLineViewport? = null,
    val panes: List<KLinePane> = emptyList(),
    val dataRevision: Long = 0,
    val viewportRevision: Long = 0,
    val paneRevision: Long = 0,
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
    private val observers = mutableListOf<(KLineStoreSnapshot, KLineStoreSnapshot) -> Unit>()

    var snapshot: KLineStoreSnapshot = KLineStoreSnapshot()
        private set

    internal fun observe(
        observer: (previous: KLineStoreSnapshot, current: KLineStoreSnapshot) -> Unit,
    ): KLineStoreSubscription {
        observers += observer
        return KLineStoreSubscription { observers.remove(observer) }
    }

    internal fun reset(
        symbol: KLineSymbol,
        period: KLinePeriod,
    ) {
        publish(KLineStoreSnapshot(
            symbol = symbol,
            period = period,
            panes = snapshot.panes,
            dataRevision = snapshot.dataRevision + 1,
            viewportRevision = snapshot.viewportRevision + 1,
            paneRevision = snapshot.paneRevision,
        ))
    }

    internal fun setLoadPhase(
        direction: KLineLoadDirection,
        phase: KLineLoadPhase,
    ) {
        val state = snapshot.loadState
        publish(snapshot.copy(
            loadState = when (direction) {
                KLineLoadDirection.INITIAL -> state.copy(initial = phase)
                KLineLoadDirection.BEFORE -> state.copy(before = phase)
                KLineLoadDirection.AFTER -> state.copy(after = phase)
            },
        ))
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
        publish(snapshot.copy(
            bars = normalized,
            hasMoreBefore = hasMoreBefore,
            hasMoreAfter = hasMoreAfter,
            dataRevision = snapshot.dataRevision + 1,
        ))
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
        publish(snapshot.copy(
            bars = merge(normalized, snapshot.bars),
            hasMoreBefore = hasMoreBefore,
            dataRevision = snapshot.dataRevision + 1,
        ))
        return insertedBefore
    }

    fun append(
        bars: List<KLineBar>,
        hasMoreAfter: Boolean,
    ) {
        publish(snapshot.copy(
            bars = merge(snapshot.bars, normalize(bars)),
            hasMoreAfter = hasMoreAfter,
            dataRevision = snapshot.dataRevision + 1,
        ))
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
        publish(snapshot.copy(
            bars = updatedBars,
            dataRevision = snapshot.dataRevision + 1,
        ))
    }

    internal fun setViewport(viewport: KLineViewport?) {
        if (snapshot.viewport == viewport) return
        publish(snapshot.copy(
            viewport = viewport,
            viewportRevision = snapshot.viewportRevision + 1,
        ))
    }

    internal fun setPanes(panes: List<KLinePane>) {
        require(panes.map(KLinePane::id).distinct().size == panes.size) { "Pane ids must be unique" }
        if (snapshot.panes == panes) return
        publish(snapshot.copy(
            panes = panes.toList(),
            paneRevision = snapshot.paneRevision + 1,
        ))
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

    private fun publish(next: KLineStoreSnapshot) {
        val previous = snapshot
        if (previous == next) return
        snapshot = next
        observers.toList().forEach { it(previous, next) }
    }
}

internal fun interface KLineStoreSubscription {
    fun cancel()
}
