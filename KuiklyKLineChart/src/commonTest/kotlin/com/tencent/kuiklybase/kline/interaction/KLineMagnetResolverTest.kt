package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KLineMagnetResolverTest {
    private val bars = listOf(
        KLineBar(1_000L, 50.0, 80.0, 20.0, 50.0),
        KLineBar(2_000L, 55.0, 90.0, 30.0, 60.0),
    )
    private val plot = KLineRect(0.0, 0.0, 100.0, 100.0)
    private val x = KLineXCoordinateSystem(plot, KLineViewport(0.0, 10.0, 10.0, 0.0), bars)
    private val y = KLineYCoordinateSystem(plot, KLineYAxis("price", 0.0, 100.0))

    @Test
    fun noneWeakAndStrongHaveDistinctSnappingBehavior() {
        assertEquals(KLineOverlayPoint(1_000L, 75.0), resolve(KLineOverlayMagnetMode.NONE, 1.0, 25.0))
        assertEquals(KLineOverlayPoint(1_000L, 80.0), resolve(KLineOverlayMagnetMode.WEAK, 1.0, 25.0))
        assertEquals(KLineOverlayPoint(1_000L, 50.0), resolve(KLineOverlayMagnetMode.WEAK, 1.0, 50.0))
        assertEquals(KLineOverlayPoint(1_000L, 80.0), resolve(KLineOverlayMagnetMode.STRONG, 1.0, 50.0))
        assertEquals(KLineOverlayPoint(2_000L, 30.0), resolve(KLineOverlayMagnetMode.STRONG, 10.0, 75.0))
    }

    @Test
    fun emptyDataAndUnrepresentableInverseValuesDoNotCreateInvalidPoints() {
        assertNull(KLineMagnetResolver.resolve(0.0, 0.0, KLineOverlayMagnetMode.NONE, emptyList(), x, y))
        val extreme = assertNotNull(resolve(KLineOverlayMagnetMode.NONE, Double.MAX_VALUE, Double.MAX_VALUE))
        assertEquals(2_000L, extreme.timestamp)
        assertTrue(extreme.value.isFinite())
        assertEquals(KLineOverlayPoint(2_000L, 90.0), resolve(KLineOverlayMagnetMode.STRONG, Double.MAX_VALUE, Double.MAX_VALUE))
    }

    private fun resolve(mode: KLineOverlayMagnetMode, px: Double, py: Double) =
        KLineMagnetResolver.resolve(px, py, mode, bars, x, y, weakThreshold = 6.0)
}
