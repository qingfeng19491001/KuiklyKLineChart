package com.tencent.kuiklybase.kline.axis

import com.tencent.kuiklybase.kline.layout.KLineRect
import kotlin.math.exp
import kotlin.math.ln

class KLineYCoordinateSystem(
    private val plot: KLineRect,
    private val axis: KLineYAxis,
) {
    private val transformedMin = transform(axis.minValue)
    private val transformedMax = transform(axis.maxValue)
    private val transformedRange = transformedMax - transformedMin

    fun valueToPixel(value: Double): Double {
        require(value.isFinite()) { "Axis value must be finite" }
        if (plot.height == 0.0) return plot.bottom
        val ratio = (transform(value) - transformedMin) / transformedRange
        return plot.bottom - ratio * plot.height
    }

    fun pixelToValue(pixel: Double): Double {
        require(pixel.isFinite()) { "Pixel coordinate must be finite" }
        if (plot.height == 0.0) return axis.minValue
        val ratio = (plot.bottom - pixel) / plot.height
        return inverse(transformedMin + ratio * transformedRange)
    }

    private fun transform(value: Double): Double = when (axis.mode) {
        KLineYAxisMode.NORMAL -> value
        KLineYAxisMode.PERCENTAGE -> (value / requireNotNull(axis.referenceValue) - 1.0) * 100.0
        KLineYAxisMode.LOGARITHMIC -> {
            require(value > 0.0) { "Logarithmic axis values must be positive" }
            ln(value)
        }
    }

    private fun inverse(value: Double): Double = when (axis.mode) {
        KLineYAxisMode.NORMAL -> value
        KLineYAxisMode.PERCENTAGE -> requireNotNull(axis.referenceValue) * (1.0 + value / 100.0)
        KLineYAxisMode.LOGARITHMIC -> exp(value)
    }
}

