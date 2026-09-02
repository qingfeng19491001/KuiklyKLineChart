package com.tencent.kuiklybase.kline.host

import kotlin.math.abs

public enum class KLineGestureClaim {
    PENDING,
    CHART,
    PARENT,
}

/** Default pan/pinch slop in vp; Host converts to pixels via [KLinePlatformHost.density]. */
public const val KLINE_TOUCH_SLOP_VP: Double = 8.0

/**
 * Cross-host claim for pan vs parent scroll vs pinch.
 * Shells collect raw touches and map [CHART]/[PARENT] onto platform intercept APIs.
 */
public class KLineGestureArbitrator {
    private var claim = KLineGestureClaim.PENDING

    public fun move(
        deltaX: Double,
        deltaY: Double,
        pointerCount: Int,
        touchSlop: Double,
    ): KLineGestureClaim {
        if (claim != KLineGestureClaim.PENDING) return claim
        if (pointerCount >= 2) {
            claim = KLineGestureClaim.CHART
            return claim
        }
        if (maxOf(abs(deltaX), abs(deltaY)) <= touchSlop) return claim
        claim = if (abs(deltaX) > abs(deltaY)) KLineGestureClaim.CHART else KLineGestureClaim.PARENT
        return claim
    }

    public fun reset() {
        claim = KLineGestureClaim.PENDING
    }
}
