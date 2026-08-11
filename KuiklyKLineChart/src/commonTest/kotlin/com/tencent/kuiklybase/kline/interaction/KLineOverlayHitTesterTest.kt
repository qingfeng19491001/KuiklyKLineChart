package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.overlay.KLineOverlayEngine
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KLineOverlayHitTesterTest {
    private val bars = (0L..10L).map { index -> KLineBar(index * 1_000L, 50.0, 60.0, 40.0, 50.0) }
    private val plot = KLineRect(0.0, 0.0, 100.0, 100.0)
    private val x = KLineXCoordinateSystem(plot, KLineViewport(0.0, 10.0, 10.0, 0.0), bars)
    private val y = KLineYCoordinateSystem(plot, KLineYAxis("price", 0.0, 100.0))
    private val tester = KLineOverlayHitTester(KLineOverlayEngine(KLineBuiltInOverlays.registry()))

    @Test
    fun hitsEveryFigureAndUsesCorrectFiniteRayAndInfiniteProjections() {
        assertFigure("horizontal_line", listOf(point(0, 50.0)), 95.0, 50.0)
        assertFigure("vertical_line", listOf(point(5_000, 50.0)), 50.0, 95.0)
        assertFigure("segment", listOf(point(2_000, 80.0), point(8_000, 20.0)), 50.0, 50.0)
        assertFigure("ray", listOf(point(2_000, 20.0), point(4_000, 40.0)), 80.0, 20.0)
        assertFigure("trend_line", listOf(point(2_000, 80.0), point(4_000, 60.0)), 80.0, 80.0)
        assertFigure("freehand", listOf(point(1_000, 20.0), point(5_000, 60.0), point(9_000, 20.0)), 30.0, 60.0)
        assertEquals(
            KLineOverlayHit("one", KLineOverlayHitType.FIGURE),
            tester.hitTest(
                listOf(instance("text_annotation", listOf(point(7_000, 70.0)))),
                74.0,
                34.0,
                x,
                y,
                hitRadius = 6.0,
                controlPointRadius = 2.0,
            ),
        )

        assertNull(hit(instance("segment", listOf(point(2_000, 80.0), point(4_000, 60.0))), 80.0, 80.0))
        assertNull(hit(instance("ray", listOf(point(4_000, 40.0), point(6_000, 60.0))), 20.0, 80.0))
    }

    @Test
    fun controlPointsWinGloballyThenFiguresHitHighestZAndReverseStableOrder() {
        val lower = instance("horizontal_line", listOf(point(0, 50.0)), id = "lower", zIndex = 1)
        val older = instance("horizontal_line", listOf(point(1_000, 50.0)), id = "older", zIndex = 5)
        val newer = instance("horizontal_line", listOf(point(2_000, 50.0)), id = "newer", zIndex = 5)
        val hidden = instance("horizontal_line", listOf(point(3_000, 50.0)), id = "hidden", zIndex = 99, visible = false)

        assertEquals(KLineOverlayHit("lower", KLineOverlayHitType.CONTROL_POINT, 0), tester.hitTest(listOf(lower, older, newer, hidden), 0.0, 50.0, x, y))
        assertEquals(KLineOverlayHit("newer", KLineOverlayHitType.FIGURE), tester.hitTest(listOf(lower, older, newer, hidden), 95.0, 50.0, x, y))
    }

    @Test
    fun largeFiniteCoordinatesUseStableProjectionForEveryDirectionalFigure() {
        val hugePlot = KLineRect(0.0, 0.0, 1e308, 1e308)
        val hugeBars = listOf(
            KLineBar(0L, 0.5, 1.0, 0.1, 0.5),
            KLineBar(1L, 0.5, 1.0, 0.1, 0.5),
        )
        val hugeX = KLineXCoordinateSystem(hugePlot, KLineViewport(0.0, 1.0, 1e308, 0.0), hugeBars)
        val hugeY = KLineYCoordinateSystem(hugePlot, KLineYAxis("huge", 0.0, 1.0))
        val midpointX = 5e307
        val midpointY = 5e307
        listOf("segment", "ray", "trend_line", "freehand").forEach { template ->
            val overlay = instance(template, listOf(point(0L, 0.0), point(1L, 1.0)), id = template)
            assertEquals(
                KLineOverlayHit(template, KLineOverlayHitType.FIGURE),
                tester.hitTest(listOf(overlay), midpointX, midpointY, hugeX, hugeY, hitRadius = 1.0),
                template,
            )
        }
    }

    @Test
    fun farRayAndInfiniteExtensionsUseLocalResidualWithoutAbsoluteProjectionCancellation() {
        val tallQueryPlot = KLineRect(0.0, 0.0, 1e308, 1.0)
        val extensionBars = listOf(
            KLineBar(0L, 0.5, 1.0, 0.1, 0.5),
            KLineBar(1L, 0.5, 1.0, 0.1, 0.5),
        )
        val extensionX = KLineXCoordinateSystem(
            tallQueryPlot,
            KLineViewport(0.0, 1.0, 1e308, 0.0),
            extensionBars,
        )
        val extensionY = KLineYCoordinateSystem(tallQueryPlot, KLineYAxis("extension", 0.0, 1.0))
        listOf("ray", "trend_line").forEach { template ->
            val overlay = instance(template, listOf(point(1L, 1.0), point(1L, 0.0)), id = template)
            assertEquals(
                KLineOverlayHit(template, KLineOverlayHitType.FIGURE),
                tester.hitTest(
                    listOf(overlay),
                    pixelX = 1e308,
                    pixelY = 1e308,
                    xCoordinates = extensionX,
                    yCoordinates = extensionY,
                    hitRadius = 1.0,
                ),
                template,
            )
        }
    }

    private fun assertFigure(template: String, points: List<KLineOverlayPoint>, px: Double, py: Double) {
        assertEquals(KLineOverlayHit("one", KLineOverlayHitType.FIGURE), hit(instance(template, points), px, py))
    }

    private fun hit(instance: KLineOverlayInstance, px: Double, py: Double) =
        tester.hitTest(listOf(instance), px, py, x, y, hitRadius = 6.0)

    private fun instance(
        template: String,
        points: List<KLineOverlayPoint>,
        id: String = "one",
        zIndex: Int = 0,
        visible: Boolean = true,
    ) = KLineOverlayInstance(id, template, paneId = "price", points = points, zIndex = zIndex, visible = visible)

    private fun point(timestamp: Long, value: Double) = KLineOverlayPoint(timestamp, value)
}
