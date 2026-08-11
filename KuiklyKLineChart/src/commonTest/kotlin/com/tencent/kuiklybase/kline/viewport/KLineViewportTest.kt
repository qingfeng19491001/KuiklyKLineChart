package com.tencent.kuiklybase.kline.viewport

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.layout.KLineRect
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
