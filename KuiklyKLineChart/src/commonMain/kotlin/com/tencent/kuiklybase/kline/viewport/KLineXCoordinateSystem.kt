package com.tencent.kuiklybase.kline.viewport

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.layout.KLineRect
import kotlin.math.roundToInt

class KLineXCoordinateSystem(
    private val plot: KLineRect,
    private val viewport: KLineViewport,
    private val bars: List<KLineBar>,
) {
    fun indexToPixel(index: Double): Double =
        plot.left + (index - viewport.startIndex) * viewport.barSpace

    fun pixelToIndex(pixel: Double): Double =
        viewport.startIndex + (pixel - plot.left) / viewport.barSpace

    fun timestampToIndex(timestamp: Long): Int? {
        if (bars.isEmpty()) return null
        val result = bars.binarySearchBy(timestamp) { it.timestamp }
        if (result >= 0) return result
        val insertion = -result - 1
        if (insertion == 0) return 0
        if (insertion == bars.size) return bars.lastIndex
        val before = bars[insertion - 1].timestamp
        val after = bars[insertion].timestamp
        val distanceBefore = timestamp.toDouble() - before.toDouble()
        val distanceAfter = after.toDouble() - timestamp.toDouble()
        return if (distanceBefore <= distanceAfter) insertion - 1 else insertion
    }

    fun indexToTimestamp(index: Double): Long? {
        if (bars.isEmpty() || !index.isFinite()) return null
        return bars[index.roundToInt().coerceIn(0, bars.lastIndex)].timestamp
    }

    fun timestampToPixel(timestamp: Long): Double? =
        timestampToIndex(timestamp)?.let { indexToPixel(it.toDouble()) }

    fun pixelToTimestamp(pixel: Double): Long? = indexToTimestamp(pixelToIndex(pixel))
}
