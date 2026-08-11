package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
import kotlin.test.Test
import kotlin.test.assertEquals

class KLineStoreTest {
    @Test
    fun initialBarsAreSortedAndDuplicateTimestampsUseTheLastValue() {
        val store = KLineStore()

        store.replaceAll(
            listOf(
                bar(timestamp = 3, close = 30.0),
                bar(timestamp = 1, close = 10.0),
                bar(timestamp = 3, close = 33.0),
                bar(timestamp = 2, close = 20.0),
            ),
        )

        assertEquals(listOf(1L, 2L, 3L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(33.0, store.snapshot.bars.last().close)
    }

    @Test
    fun realtimeBarReplacesTailOrAppendsNewTail() {
        val store = KLineStore()
        store.replaceAll(listOf(bar(1, 10.0), bar(2, 20.0)))

        store.applyRealtime(bar(2, 22.0))
        store.applyRealtime(bar(3, 30.0))

        assertEquals(listOf(10.0, 22.0, 30.0), store.snapshot.bars.map(KLineBar::close))
    }

    private fun bar(timestamp: Long, close: Double): KLineBar = KLineBar(
        timestamp = timestamp,
        open = close,
        high = close,
        low = close,
        close = close,
    )
}
