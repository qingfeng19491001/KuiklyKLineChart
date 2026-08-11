package com.tencent.kuiklybase.kline.viewport

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.layout.KLineRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KLineViewportTest {
    private val config = KLineViewportConfig(
        defaultBarSpace = 10.0,
        minBarSpace = 2.0,
        maxBarSpace = 40.0,
        maxRightOffsetBars = 20.0,
    )
    private val plot = KLineRect(left = 20.0, top = 10.0, right = 220.0, bottom = 110.0)

    @Test
    fun latestViewportAndTimestampCoordinatesRoundTrip() {
        val bars = (0L until 100L).map(::bar)
        val viewport = KLineViewportEngine.initial(
            dataCount = bars.size,
            plotWidth = plot.width,
            config = config,
        )
        val coordinates = KLineXCoordinateSystem(plot, viewport, bars)

        assertEquals(79.0, viewport.startIndex, 1e-9)
        assertEquals(99.0, viewport.endIndex, 1e-9)
        assertEquals(20.0, coordinates.indexToPixel(79.0), 1e-9)
        assertEquals(220.0, coordinates.indexToPixel(99.0), 1e-9)
        assertEquals(120.0, coordinates.timestampToPixel(89_000L)!!, 1e-9)
        assertEquals(89_000L, coordinates.pixelToTimestamp(120.0))
    }

    @Test
    fun timestampQueriesRemainExactAcrossLongExtremes() {
        val bars = listOf(barAt(Long.MIN_VALUE), barAt(Long.MAX_VALUE - 2), barAt(Long.MAX_VALUE))
        val coordinates = KLineXCoordinateSystem(plot, KLineViewport(0.0, 2.0, 10.0, rightOffset = 0.0), bars)

        assertEquals(1, coordinates.timestampToIndex(Long.MAX_VALUE - 1))
        assertEquals(2, coordinates.exactTimestampToIndex(Long.MAX_VALUE))
        assertEquals(null, coordinates.exactTimestampToIndex(0))
        val extremes = KLineXCoordinateSystem(
            plot,
            KLineViewport(0.0, 1.0, 10.0, rightOffset = 0.0),
            listOf(barAt(Long.MIN_VALUE), barAt(Long.MAX_VALUE)),
        )
        assertEquals(1, extremes.timestampToIndex(0))
    }

    @Test
    fun timestampQueriesUseLogarithmicElementAccessAtScale() {
        listOf(1_000, 10_000, 100_000).forEach { size ->
            val bars = CountingBars(List(size) { index -> barAt(index.toLong() * 2) })
            val coordinates = KLineXCoordinateSystem(plot, KLineViewport(0.0, 2.0, 10.0, 0.0), bars)
            bars.accessCount = 0
            assertEquals(size - 1, coordinates.exactTimestampToIndex((size - 1L) * 2))
            assertTrue(bars.accessCount <= 20, "size=$size accesses=${bars.accessCount}")
            bars.accessCount = 0
            assertEquals(size - 2, coordinates.timestampToIndex((size - 2L) * 2 + 1))
            assertTrue(bars.accessCount <= 22, "nearest size=$size accesses=${bars.accessCount}")
        }
    }

    @Test
    fun panZoomAndHistoryPrependPreserveExpectedAnchors() {
        val bars = (0L until 100L).map(::bar)
        val initial = KLineViewportEngine.initial(bars.size, plot.width, config)

        val panned = KLineViewportEngine.panByPixels(
            viewport = initial,
            pixelDelta = 30.0,
            dataCount = bars.size,
            plotWidth = plot.width,
            config = config,
        )
        assertEquals(76.0, panned.startIndex, 1e-9)
        assertEquals(96.0, panned.endIndex, 1e-9)

        val zoomed = KLineViewportEngine.zoomAtPixel(
            viewport = initial,
            factor = 2.0,
            focalPixel = 120.0,
            plotLeft = plot.left,
            plotWidth = plot.width,
            dataCount = bars.size,
            config = config,
        )
        assertEquals(20.0, zoomed.barSpace, 1e-9)
        assertEquals(84.0, zoomed.startIndex, 1e-9)
        assertEquals(94.0, zoomed.endIndex, 1e-9)
        assertEquals(89.0, zoomed.zoomAnchor!!, 1e-9)

        val shifted = KLineViewportEngine.preserveAfterPrepend(initial, insertedCount = 2)
        val prependedBars = listOf(bar(-2), bar(-1)) + bars
        val shiftedCoordinates = KLineXCoordinateSystem(plot, shifted, prependedBars)
        assertEquals(120.0, shiftedCoordinates.timestampToPixel(89_000L)!!, 1e-9)
    }

    private fun bar(index: Long): KLineBar = KLineBar(
        timestamp = index * 1_000L,
        open = 10.0,
        high = 10.0,
        low = 10.0,
        close = 10.0,
    )

    private fun barAt(timestamp: Long): KLineBar = KLineBar(timestamp, 1.0, 1.0, 1.0, 1.0)

    private class CountingBars(private val values: List<KLineBar>) : AbstractList<KLineBar>() {
        var accessCount = 0
        override val size: Int get() = values.size
        override fun get(index: Int): KLineBar {
            accessCount++
            return values[index]
        }
    }
}
