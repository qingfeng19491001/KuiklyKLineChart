package com.tencent.kuiklybase.kline.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistentKLineBarListTest {
    @Test
    fun listIteratorHonorsStartMiddleLeafBoundaryAndEndContracts() {
        val bars = persistentKLineBars(List(130) { bar(it) }, null)

        val start = bars.listIterator(0)
        assertFalse(start.hasPrevious())
        assertEquals(0, start.nextIndex())
        assertEquals(0L, start.next().timestamp)
        assertEquals(0L, start.previous().timestamp)

        val boundary = bars.listIterator(64)
        assertEquals(64L, boundary.next().timestamp)
        assertEquals(64L, boundary.previous().timestamp)
        assertEquals(63L, boundary.previous().timestamp)
        assertEquals(63L, boundary.next().timestamp)

        val middle = bars.listIterator(97)
        assertEquals(96, middle.previousIndex())
        assertEquals(97L, middle.next().timestamp)

        val end = bars.listIterator(bars.size)
        assertFalse(end.hasNext())
        assertEquals(130, end.nextIndex())
        assertEquals(129L, end.previous().timestamp)

        val empty = emptyPersistentKLineBars().listIterator(0)
        assertFalse(empty.hasNext())
        assertFalse(empty.hasPrevious())
    }

    @Test
    fun partialIterationLocatesInLogarithmicNodesAndVisitsOnlyReturnedItems() {
        val bars = persistentKLineBars(List(100_000) { bar(it) }, null)
        val tracker = CountingTracker()

        val iterator = bars.trackedListIterator(50_000, tracker)
        val actual = List(7) { iterator.next().timestamp }

        assertEquals((50_000L..50_006L).toList(), actual)
        assertTrue(tracker.nodeVisits <= 32, "nodes=${tracker.nodeVisits}")
        assertEquals(7, tracker.itemGets)
    }

    @Test
    fun listIteratorRejectsIndicesOutsideTheListBounds() {
        val bars = persistentKLineBars(List(3) { bar(it) }, null)

        assertFailsWith<IndexOutOfBoundsException> { bars.listIterator(-1) }
        assertFailsWith<IndexOutOfBoundsException> { bars.listIterator(bars.size + 1) }
    }

    private fun bar(index: Int) = KLineBar(index.toLong(), 1.0, 1.0, 1.0, 1.0)

    private class CountingTracker : KLineBarRopeTracker {
        var nodeVisits = 0
        var itemGets = 0
        override fun recordBarRopeSplice() = Unit
        override fun recordBarRopeNodeVisit() { nodeVisits++ }
        override fun recordBarLeafItemsCopied(count: Int) = Unit
        override fun recordBarIteratorItemGet() { itemGets++ }
        override fun recordBarIteratorNodeVisit() { nodeVisits++ }
    }
}
