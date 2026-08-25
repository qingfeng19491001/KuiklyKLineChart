package com.tencent.kuiklybase.kline.viewport

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.exactTimestampIndex
import com.tencent.kuiklybase.kline.data.nearestTimestampIndex
import com.tencent.kuiklybase.kline.layout.KLineRect
import kotlin.math.roundToInt

internal class KLineXCoordinateSystem(
    private val plot: KLineRect,
    private val viewport: KLineViewport,
    private val bars: List<KLineBar>,
) {
    fun indexToPixel(index: Double): Double =
        plot.left + (index - viewport.startIndex) * viewport.barSpace

    fun pixelToIndex(pixel: Double): Double =
        viewport.startIndex + (pixel - plot.left) / viewport.barSpace

    fun timestampToIndex(timestamp: Long): Int? {
        return bars.nearestTimestampIndex(timestamp)
    }

    fun exactTimestampToIndex(timestamp: Long): Int? = bars.exactTimestampIndex(timestamp)

    fun indexToTimestamp(index: Double): Long? {
        if (bars.isEmpty() || !index.isFinite()) return null
        return bars[index.roundToInt().coerceIn(0, bars.lastIndex)].timestamp
    }

    fun timestampToPixel(timestamp: Long): Double? =
        timestampToIndex(timestamp)?.let { indexToPixel(it.toDouble()) }

    fun pixelToTimestamp(pixel: Double): Long? = indexToTimestamp(pixelToIndex(pixel))
}
