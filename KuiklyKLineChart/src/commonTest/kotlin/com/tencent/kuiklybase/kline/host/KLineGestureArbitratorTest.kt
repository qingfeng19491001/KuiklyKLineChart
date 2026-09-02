package com.tencent.kuiklybase.kline.host

import kotlin.test.Test
import kotlin.test.assertEquals

class KLineGestureArbitratorTest {
    @Test
    fun staysPendingInsideSlopAndClaimsHorizontalMovement() {
        val arbitrator = KLineGestureArbitrator()

        assertEquals(KLineGestureClaim.PENDING, arbitrator.move(4.0, 3.0, pointerCount = 1, touchSlop = 8.0))
        assertEquals(KLineGestureClaim.CHART, arbitrator.move(10.0, 4.0, pointerCount = 1, touchSlop = 8.0))
        assertEquals(KLineGestureClaim.CHART, arbitrator.move(4.0, 20.0, pointerCount = 1, touchSlop = 8.0))
    }

    @Test
    fun releasesVerticalMovementToParent() {
        val arbitrator = KLineGestureArbitrator()

        assertEquals(KLineGestureClaim.PARENT, arbitrator.move(4.0, 10.0, pointerCount = 1, touchSlop = 8.0))
        assertEquals(KLineGestureClaim.PARENT, arbitrator.move(20.0, 2.0, pointerCount = 1, touchSlop = 8.0))
    }

    @Test
    fun multiplePointersAlwaysClaimChartUntilReset() {
        val arbitrator = KLineGestureArbitrator()

        assertEquals(KLineGestureClaim.CHART, arbitrator.move(0.0, 0.0, pointerCount = 2, touchSlop = 8.0))
        arbitrator.reset()
        assertEquals(KLineGestureClaim.PENDING, arbitrator.move(1.0, 1.0, pointerCount = 1, touchSlop = 8.0))
    }

    @Test
    fun hostConvertsVpSlopToPixels() {
        val host = KLinePlatformHost()
        host.density = 3.0

        assertEquals(KLineGestureClaim.PENDING, host.claimPointerMove(20.0, 4.0, pointerCount = 1))
        assertEquals(KLineGestureClaim.CHART, host.claimPointerMove(25.0, 4.0, pointerCount = 1))
        host.resetGestureClaim()
        assertEquals(KLineGestureClaim.PARENT, host.claimPointerMove(4.0, 25.0, pointerCount = 1))
    }
}
