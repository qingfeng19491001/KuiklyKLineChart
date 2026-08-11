package com.tencent.kuiklybase.kline.pane

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.axis.KLineYAxisMode
import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.layout.KLineRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KLinePaneTest {
    private val bounds = KLineRect(left = 0.0, top = 0.0, right = 300.0, bottom = 500.0)
    private val priceAxis = KLineYAxis(
        id = "price",
        minValue = 90.0,
        maxValue = 110.0,
        mode = KLineYAxisMode.PERCENTAGE,
        referenceValue = 100.0,
    )
    private val volumeAxis = KLineYAxis(
        id = "volume",
        minValue = 1.0,
        maxValue = 100.0,
        mode = KLineYAxisMode.LOGARITHMIC,
    )

    @Test
    fun percentageAndLogarithmicAxesRoundTripValues() {
        val percent = KLineYCoordinateSystem(bounds, priceAxis)
        val logarithmic = KLineYCoordinateSystem(
            KLineRect(0.0, 0.0, 100.0, 100.0),
            volumeAxis,
        )

        assertEquals(250.0, percent.valueToPixel(100.0), 1e-9)
        assertEquals(100.0, percent.pixelToValue(250.0), 1e-9)
        assertEquals(50.0, logarithmic.valueToPixel(10.0), 1e-9)
        assertEquals(10.0, logarithmic.pixelToValue(50.0), 1e-9)
    }

    @Test
    fun paneLayoutHonorsOrderWeightMinimumAndState() {
        val price = pane(
            id = "price",
            kind = KLinePaneKind.PRICE,
            order = 0,
            weight = 3.0,
            minHeight = 100.0,
            axis = priceAxis,
        )
        val volume = pane(
            id = "volume",
            kind = KLinePaneKind.INDICATOR,
            order = 1,
            weight = 1.0,
            minHeight = 60.0,
            axis = volumeAxis,
        )

        val normal = KLinePaneLayoutEngine.layout(
            panes = listOf(volume, price),
            bounds = bounds,
            separatorHeight = 10.0,
        )
        assertEquals(listOf("price", "volume"), normal.map(KLinePaneLayout::paneId))
        assertEquals(347.5, normal[0].rect.height, 1e-9)
        assertEquals(142.5, normal[1].rect.height, 1e-9)

        val minimized = KLinePaneLayoutEngine.layout(
            panes = listOf(price, volume.copy(state = KLinePaneState.MINIMIZED)),
            bounds = bounds,
            separatorHeight = 10.0,
        )
        assertEquals(430.0, minimized[0].rect.height, 1e-9)
        assertEquals(60.0, minimized[1].rect.height, 1e-9)

        val maximized = KLinePaneLayoutEngine.layout(
            panes = listOf(price, volume.copy(state = KLinePaneState.MAXIMIZED)),
            bounds = bounds,
            separatorHeight = 10.0,
        )
        assertFalse(maximized.first { it.paneId == "price" }.visible)
        assertTrue(maximized.first { it.paneId == "volume" }.visible)
        assertEquals(500.0, maximized.first { it.paneId == "volume" }.rect.height, 1e-9)
    }

    private fun pane(
        id: String,
        kind: KLinePaneKind,
        order: Int,
        weight: Double,
        minHeight: Double,
        axis: KLineYAxis,
    ): KLinePane = KLinePane(
        id = id,
        kind = kind,
        order = order,
        weight = weight,
        minHeight = minHeight,
        yAxes = listOf(axis),
    )
}
