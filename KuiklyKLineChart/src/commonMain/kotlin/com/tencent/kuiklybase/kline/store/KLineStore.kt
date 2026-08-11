package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar

data class KLineStoreSnapshot(
    val bars: List<KLineBar> = emptyList(),
    val dataRevision: Long = 0,
)

class KLineStore {
    var snapshot: KLineStoreSnapshot = KLineStoreSnapshot()
        private set

    fun replaceAll(bars: List<KLineBar>) {
        val normalized = bars
            .associateBy(KLineBar::timestamp)
            .values
            .sortedBy(KLineBar::timestamp)
        snapshot = snapshot.copy(
            bars = normalized,
            dataRevision = snapshot.dataRevision + 1,
        )
    }

    fun applyRealtime(bar: KLineBar) {
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
}
