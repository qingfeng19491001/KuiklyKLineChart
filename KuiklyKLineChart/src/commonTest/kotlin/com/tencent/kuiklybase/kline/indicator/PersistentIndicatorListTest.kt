package com.tencent.kuiklybase.kline.indicator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentIndicatorListTest {
    @Test
    fun sliceConcatAndReplaceShareTheHundredThousandElementPrefix() {
        val source = CountingList(100_000)
        val full = PersistentIndicatorList.from(source)
        assertEquals(100_000, source.accessCount)
        source.accessCount = 0

        val slice = full.slice(5, 99_995)
        val appendedSource = CountingList(3, offset = 100_000)
        val appended = full.concat(appendedSource)
        val replacementSource = CountingList(1, offset = -1)
        val replaced = full.replaceRange(99_999, 100_000, replacementSource)

        assertEquals(0, source.accessCount)
        assertEquals(3, appendedSource.accessCount)
        assertEquals(1, replacementSource.accessCount)
        assertEquals(5, slice.first())
        assertEquals(99_994, slice.last())
        assertEquals(100_002, appended.last())
        assertEquals(-1, replaced.last())
    }

    @Test
    fun repeatedTailReplacementRemainsBalancedWithLogarithmicReads() {
        var values = PersistentIndicatorList.from(List(100_000) { it })
        repeat(1_024) { update -> values = values.replaceRange(values.lastIndex, values.size, listOf(update)) }

        assertEquals(12, values.treeHeight)
        assertEquals(12, values.nodeVisits(0))
        assertEquals(10, values.nodeVisits(values.lastIndex))
        assertTrue(values.treeHeight <= 14, "height=${values.treeHeight}")
        assertTrue(values.nodeVisits(0) <= 14, "first visits=${values.nodeVisits(0)}")
        assertTrue(values.nodeVisits(values.lastIndex) <= 14, "last visits=${values.nodeVisits(values.lastIndex)}")
        assertEquals(0, values.first())
        assertEquals(1_023, values.last())
        val expected = List(99_999) { it } + 1_023
        assertEquals(expected, values.toList())
        assertEquals(expected, values)
        assertEquals(expected.hashCode(), values.hashCode())
    }

    @Test
    fun listIteratorSupportsBoundariesBidirectionalTraversalAndInvalidIndices() {
        val values = PersistentIndicatorList.from(List(130) { it })

        val start = values.listIterator(0)
        assertFalse(start.hasPrevious())
        assertEquals(0, start.next())
        assertEquals(0, start.previous())

        val boundary = values.listIterator(64)
        assertEquals(64, boundary.next())
        assertEquals(64, boundary.previous())
        assertEquals(63, boundary.previous())
        assertEquals(63, boundary.next())

        val end = values.listIterator(values.size)
        assertFalse(end.hasNext())
        assertEquals(129, end.previous())
        assertFailsWith<IndexOutOfBoundsException> { values.listIterator(-1) }
        assertFailsWith<IndexOutOfBoundsException> { values.listIterator(values.size + 1) }
        val empty = PersistentIndicatorList.from(emptyList<Int>()).listIterator(0)
        assertFalse(empty.hasNext())
        assertFalse(empty.hasPrevious())
    }

    @Test
    fun middlePartialIterationUsesLogarithmicPositioningAndOnlySevenGets() {
        val values = PersistentIndicatorList.from(List(100_000) { it })
        val tracker = CountingIteratorTracker()

        val iterator = values.trackedListIterator(50_000, tracker)
        val actual = List(7) { iterator.next() }

        assertEquals((50_000..50_006).toList(), actual)
        assertTrue(tracker.nodeVisits <= 32, "nodes=${tracker.nodeVisits}")
        assertEquals(7, tracker.itemGets)
    }

    @Test
    fun persistentReplacementReusesItsRootWithoutClaimingLeafCopies() {
        val tracker = KLinePerformanceTracker()
        val key = KLinePerformanceKey("rope", "rope")
        val values = PersistentIndicatorList.from(List(100_000) { it })

        values.replaceRange(0, values.size, values, tracker, key)

        assertEquals(1, tracker.metrics("rope", "rope").ropeSplices)
        assertEquals(0, tracker.metrics("rope", "rope").ropeLeafItemsCopied)

        tracker.reset()
        values.replaceRange(0, values.size, listOf(1, 2, 3), tracker, key)
        assertEquals(3, tracker.metrics("rope", "rope").ropeLeafItemsCopied)
    }

    private class CountingList(
        override val size: Int,
        private val offset: Int = 0,
    ) : AbstractList<Int>() {
        var accessCount = 0
        override fun get(index: Int): Int {
            accessCount++
            return offset + index
        }
    }

    private class CountingIteratorTracker : PersistentIndicatorIteratorTracker {
        var nodeVisits = 0
        var itemGets = 0
        override fun recordNodeVisit() { nodeVisits++ }
        override fun recordItemGet() { itemGets++ }
    }
}
