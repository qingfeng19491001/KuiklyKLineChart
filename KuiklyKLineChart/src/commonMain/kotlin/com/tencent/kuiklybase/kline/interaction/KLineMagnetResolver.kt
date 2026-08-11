package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.math.abs

object KLineMagnetResolver {
    fun resolve(
        pixelX: Double,
        pixelY: Double,
        mode: KLineOverlayMagnetMode,
        bars: List<KLineBar>,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
        weakThreshold: Double = 8.0,
    ): KLineOverlayPoint? {
        if (!pixelX.isFinite() || !pixelY.isFinite() || bars.isEmpty() ||
            !weakThreshold.isFinite() || weakThreshold < 0.0) return null
        val timestamp = xCoordinates.pixelToTimestamp(pixelX) ?: return null
        val index = bars.binarySearchBy(timestamp) { it.timestamp }
        if (index < 0) return null
        val bar = bars[index]
        val rawValue = yCoordinates.pixelToValue(pixelY)
        if (!rawValue.isFinite()) return null
        if (mode == KLineOverlayMagnetMode.NONE) return KLineOverlayPoint(timestamp, rawValue)

        val highDistance = abs(pixelY - yCoordinates.valueToPixel(bar.high))
        val lowDistance = abs(pixelY - yCoordinates.valueToPixel(bar.low))
        val snappedValue = if (highDistance <= lowDistance) bar.high else bar.low
        val nearestDistance = minOf(highDistance, lowDistance)
        return if (mode == KLineOverlayMagnetMode.STRONG || nearestDistance <= weakThreshold) {
            KLineOverlayPoint(timestamp, snappedValue)
        } else {
            KLineOverlayPoint(timestamp, rawValue)
        }
    }
}
