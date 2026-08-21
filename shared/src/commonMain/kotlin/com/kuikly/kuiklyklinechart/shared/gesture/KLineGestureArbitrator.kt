package com.kuikly.kuiklyklinechart.shared.gesture

import kotlin.math.abs

internal enum class KLineGestureClaim {
    PENDING,
    CHART,
    PARENT,
}

internal class KLineGestureArbitrator(
    private val touchSlop: Double,
) {
    private var claim = KLineGestureClaim.PENDING

    fun move(deltaX: Double, deltaY: Double, pointerCount: Int): KLineGestureClaim {
        if (claim != KLineGestureClaim.PENDING) return claim
        if (pointerCount >= 2) {
            claim = KLineGestureClaim.CHART
            return claim
        }
        if (maxOf(abs(deltaX), abs(deltaY)) <= touchSlop) return claim
        claim = if (abs(deltaX) > abs(deltaY)) KLineGestureClaim.CHART else KLineGestureClaim.PARENT
        return claim
    }

    fun reset() {
        claim = KLineGestureClaim.PENDING
    }
}
