package com.tencent.kuiklybase.kline.signal

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class KLineSignalTest {
    @Test
    fun signalSetValidatesValuesAndUniqueIdsAndOwnsAnImmutableCopy() {
        val mutable = mutableListOf(signal("one"))
        val set = KLineSignalSet(mutable)
        mutable.clear()

        assertEquals(listOf("one"), set.signals.map { it.id })
        assertNotSame(mutable, set.signals)
        assertFailsWith<IllegalArgumentException> { KLineSignalSet(listOf(signal("same"), signal("same"))) }
        assertFailsWith<IllegalArgumentException> { signal("bad", value = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { signal("bad", confidence = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun signalUsesExistingOverlayAndHitTesterAndReturnsSemanticSignal() {
        val signal = signal("buy", timestamp = 1L, value = 50.0)
        val set = KLineSignalSet(listOf(signal))
        val overlays = set.toOverlayInstances()
        val rect = KLineRect(0.0, 0.0, 100.0, 100.0)
        val bars = listOf(KLineBar(0L, 50.0, 55.0, 45.0, 50.0), KLineBar(1L, 50.0, 55.0, 45.0, 50.0))
        val x = KLineXCoordinateSystem(rect, KLineViewport(0.0, 1.0, 100.0, 0.0), bars)
        val y = KLineYCoordinateSystem(rect, KLineYAxis("price", 0.0, 100.0))

        assertEquals(signal, set.hitTest(overlays, "price", 100.0, 50.0, x, y, hitRadius = 8.0))
        assertEquals("text_annotation", overlays.single().templateName)
        assertEquals(true, overlays.single().locked)
    }

    private fun signal(
        id: String,
        timestamp: Long = 1L,
        value: Double = 10.0,
        confidence: Double? = null,
    ) = KLineSignal(id, timestamp, value, KLineSignalType.BUY, "Buy", "Reason", confidence)
}
