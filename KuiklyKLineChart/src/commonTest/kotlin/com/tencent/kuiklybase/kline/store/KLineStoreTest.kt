package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode
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

    @Test
    fun invalidBarsAreRejectedAndReportedWithoutDiscardingValidBars() {
        val errors = mutableListOf<KLineError>()
        val store = KLineStore(onError = errors::add)

        store.replaceAll(
            listOf(
                bar(1, 10.0),
                KLineBar(timestamp = 2, open = 12.0, high = 11.0, low = 9.0, close = 10.0),
                KLineBar(
                    timestamp = 3,
                    open = 10.0,
                    high = 11.0,
                    low = 9.0,
                    close = 10.0,
                    volume = -1.0,
                ),
            ),
        )

        assertEquals(listOf(1L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(2, errors.size)
        assertEquals(listOf(KLineErrorCode.INVALID_DATA, KLineErrorCode.INVALID_DATA), errors.map(KLineError::code))
    }

    @Test
    fun historicalPagesMergeInTimestampOrderAndUpdateBoundaries() {
        val store = KLineStore()
        store.replaceAll(
            bars = listOf(bar(3, 30.0), bar(4, 40.0)),
            hasMoreBefore = true,
            hasMoreAfter = true,
        )

        val insertedBefore = store.prepend(
            bars = listOf(bar(2, 20.0), bar(1, 10.0)),
            hasMoreBefore = false,
        )
        store.append(
            bars = listOf(bar(6, 60.0), bar(5, 50.0)),
            hasMoreAfter = false,
        )
        store.applyRealtime(bar(4, 404.0))

        assertEquals(2, insertedBefore)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(false, store.snapshot.hasMoreBefore)
        assertEquals(false, store.snapshot.hasMoreAfter)
        assertEquals(40.0, store.snapshot.bars.first { it.timestamp == 4L }.close)
    }

    private fun bar(timestamp: Long, close: Double): KLineBar = KLineBar(
        timestamp = timestamp,
        open = close,
        high = close,
        low = close,
        close = close,
    )
}
