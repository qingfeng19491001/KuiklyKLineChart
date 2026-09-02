package com.tencent.kuiklybase.kline.overlay

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.indicator.KLineMovingAverageIndicator
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KLineOverlayEngineTest {
    @Test
    fun registriesKeepIndicatorAndOverlayNamespacesAndRevisionsIsolated() {
        val first = KLineExtensionRegistry(listOf(KLineMovingAverageIndicator))
        val second = KLineExtensionRegistry()
        val indicatorRevision = first.indicatorRevision

        val sameNameOverlay = overlayTemplate(KLineMovingAverageIndicator.name)
        first.registerOverlay(sameNameOverlay)

        assertEquals(indicatorRevision, first.indicatorRevision)
        assertEquals(1L, first.overlayRevision)
        assertEquals(KLineMovingAverageIndicator, first.find(KLineMovingAverageIndicator.name))
        assertEquals(sameNameOverlay, first.findOverlay(KLineMovingAverageIndicator.name))
        assertNull(second.findOverlay(KLineMovingAverageIndicator.name))
        assertEquals(listOf(KLineMovingAverageIndicator), first.templates())
        assertEquals(listOf(sameNameOverlay), first.overlayTemplates())
        assertEquals(sameNameOverlay, first.unregisterOverlay(KLineMovingAverageIndicator.name))
    }

    @Test
    fun publicDslOverlayNamesResolveToBuiltInTemplates() {
        val registry = KLineBuiltInOverlays.registry()
        assertEquals("horizontal_line", registry.findOverlay("HORIZONTAL_LINE")!!.name)
        assertEquals("vertical_line", registry.findOverlay("VERTICAL_LINE")!!.name)
        assertEquals("trend_line", registry.findOverlay("TREND_LINE")!!.name)
        assertEquals("text_annotation", registry.findOverlay("TEXT")!!.name)
        assertEquals("text_annotation", registry.findOverlay("text_annotation")!!.name)
        assertEquals("MyOverlay", canonicalizeOverlayTemplateName("MyOverlay"))
        registry.registerOverlay(overlayTemplate("MyOverlay"))
        assertEquals("MyOverlay", registry.findOverlay("MyOverlay")!!.name)
    }

    @Test
    fun builtInsExposeTheCompleteStableTemplateSetAndKnownGeometry() {
        assertEquals(
            listOf(
                "horizontal_line", "vertical_line", "segment", "trend_line", "ray", "price_line",
                "parallel_lines", "price_channel", "fibonacci_retracement", "text_annotation", "freehand",
            ),
            KLineBuiltInOverlays.templates.map(KLineOverlayTemplate::name),
        )
        val engine = KLineOverlayEngine(KLineBuiltInOverlays.registry())
        val style = KLineOverlayFigureStyle(color = "#123456", lineWidth = 3.0, lineDash = listOf(2.0, 4.0))
        assertEquals(KLineOverlayDrawingMode.POINT_BY_POINT, KLineBuiltInOverlays.SEGMENT.drawingMode)
        assertEquals(KLineOverlayDrawingMode.CONTINUOUS, KLineBuiltInOverlays.FREEHAND.drawingMode)
        assertEquals(
            KLineOverlayFigure.HorizontalLine(20.0, style),
            engine.createFigures(instance("horizontal_line", listOf(point(10, 20.0)), style)).single(),
        )
        assertEquals(
            KLineOverlayFigure.VerticalLine(10, style),
            engine.createFigures(instance("vertical_line", listOf(point(10, 20.0)), style)).single(),
        )

        assertEquals(
            KLineOverlayFigure.Segment(point(10, 20.0), point(30, 40.0), style),
            engine.createFigures(instance("segment", listOf(point(10, 20.0), point(30, 40.0)), style)).single(),
        )
        assertEquals(
            KLineOverlayFigure.InfiniteLine(point(10, 20.0), point(30, 40.0), style),
            engine.createFigures(instance("trend_line", listOf(point(10, 20.0), point(30, 40.0)), style)).single(),
        )
        assertEquals(
            KLineOverlayFigure.Ray(point(10, 20.0), point(30, 40.0), style),
            engine.createFigures(instance("ray", listOf(point(10, 20.0), point(30, 40.0)), style)).single(),
        )
        assertEquals(
            listOf(
                KLineOverlayFigure.HorizontalLine(20.0, style),
                KLineOverlayFigure.Text(point(10, 20.0), "20", style),
            ),
            engine.createFigures(instance("price_line", listOf(point(10, 20.0)), style)),
        )
        assertEquals(
            listOf(
                KLineOverlayFigure.InfiniteLine(point(10, 10.0), point(20, 20.0), style),
                KLineOverlayFigure.InfiniteLine(point(30, 40.0), point(40, 50.0), style),
            ),
            engine.createFigures(instance("parallel_lines", listOf(point(10, 10.0), point(20, 20.0), point(30, 40.0)), style)),
        )
        assertEquals(
            listOf(10.0, 25.0, 40.0),
            engine.createFigures(instance("price_channel", listOf(point(10, 10.0), point(20, 20.0), point(30, 40.0)), style))
                .map { (it as KLineOverlayFigure.InfiniteLine).start.value },
        )
        val fibonacciValues = engine
            .createFigures(instance("fibonacci_retracement", listOf(point(10, 100.0), point(20, 0.0)), style))
            .map { (it as KLineOverlayFigure.Segment).start.value }
        listOf(100.0, 76.4, 61.8, 50.0, 38.2, 23.6, 0.0).zip(fibonacciValues).forEach { (expected, actual) ->
            assertEquals(expected, actual, 1e-9)
        }
        assertEquals(
            KLineOverlayFigure.Text(point(10, 20.0), "earnings", style),
            engine.createFigures(
                instance("text_annotation", listOf(point(10, 20.0)), style, extendData = mapOf("text" to "earnings")),
            ).single(),
        )
        assertEquals(
            KLineOverlayFigure.Polyline(listOf(point(10, 20.0), point(20, 25.0), point(30, 18.0)), style),
            engine.createFigures(
                instance("freehand", listOf(point(10, 20.0), point(20, 25.0), point(30, 18.0)), style),
            ).single(),
        )
    }

    @Test
    fun engineRejectsUnknownTemplatesAndInsufficientPoints() {
        val engine = KLineOverlayEngine(KLineBuiltInOverlays.registry())

        assertFailsWith<IllegalArgumentException> { engine.createFigures(instance("missing", listOf(point(1, 1.0)))) }
        assertFailsWith<IllegalArgumentException> { engine.createFigures(instance("segment", listOf(point(1, 1.0)))) }
        assertFailsWith<IllegalArgumentException> {
            engine.createFigures(instance("segment", listOf(point(1, 1.0), point(2, 2.0), point(3, 3.0))))
        }
        assertEquals(
            emptyList(),
            engine.createFigures(
                instance("segment", listOf(point(1, 1.0), point(2, 2.0))).copy(visible = false),
            ),
        )
    }

    @Test
    fun modelsRejectBlankAndNonFiniteInput() {
        assertFailsWith<IllegalArgumentException> { KLineOverlayPoint(1, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { KLineOverlayFigureStyle(lineWidth = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> {
            instance("segment", listOf(point(1, 1.0))).copy(groupId = " ")
        }
    }

    @Test
    fun collectionInputsAreDefensivelySnapshottedAtPublicModelBoundaries() {
        val mutableDash = mutableListOf(2.0, 4.0)
        val style = KLineOverlayFigureStyle(lineDash = mutableDash)
        val mutablePolylinePoints = mutableListOf(point(1, 10.0), point(2, 20.0))
        val polyline = KLineOverlayFigure.Polyline(mutablePolylinePoints, style)
        val mutableInstancePoints = mutableListOf(point(10, 100.0), point(20, 200.0))
        val mutableStyles = mutableMapOf(KLineOverlayStyleKey.DEFAULT to style)
        val mutableExtendData = mutableMapOf("source" to "initial")
        val overlay = KLineOverlayInstance(
            id = "overlay-snapshot",
            templateName = KLineBuiltInOverlays.FREEHAND_NAME,
            paneId = "price",
            points = mutableInstancePoints,
            styles = mutableStyles,
            extendData = mutableExtendData,
        )
        val configPoints = mutableListOf(point(30, 300.0))
        val configStyles = mutableMapOf(KLineOverlayStyleKey.DEFAULT to style)
        val configData = mutableMapOf("label" to "kept")
        val config = KLineOverlayConfig(
            templateName = KLineBuiltInOverlays.HORIZONTAL_LINE_NAME,
            paneId = "price",
            points = configPoints,
            styles = configStyles,
            extendData = configData,
        )

        mutableDash += Double.NaN
        mutablePolylinePoints.clear()
        mutableInstancePoints[0] = point(10, Double.MAX_VALUE)
        mutableStyles.clear()
        mutableExtendData["source"] = "changed"
        configPoints.clear()
        configStyles.clear()
        configData.clear()

        assertEquals(listOf(2.0, 4.0), style.lineDash)
        assertEquals(listOf(point(1, 10.0), point(2, 20.0)), polyline.points)
        assertEquals(listOf(point(10, 100.0), point(20, 200.0)), overlay.points)
        assertEquals(style, overlay.styles.getValue(KLineOverlayStyleKey.DEFAULT))
        assertEquals("initial", overlay.extendData.getValue("source"))
        assertEquals(listOf(point(30, 300.0)), config.points)
        assertEquals(style, config.styles.getValue(KLineOverlayStyleKey.DEFAULT))
        assertEquals("kept", config.extendData.getValue("label"))
        assertEquals(
            KLineOverlayFigure.Polyline(listOf(point(10, 100.0), point(20, 200.0)), style),
            KLineOverlayEngine(KLineBuiltInOverlays.registry()).createFigures(overlay).single(),
        )
    }

    @Test
    fun builtInGeometryHandlesNumericBoundariesWithoutSilentOverflowOrDegeneration() {
        val engine = KLineOverlayEngine(KLineBuiltInOverlays.registry())

        val horizontal = engine.createFigures(instance("horizontal_line", listOf(point(1, Double.MAX_VALUE))))
            .single() as KLineOverlayFigure.HorizontalLine
        assertTrue(horizontal.value.isFinite())

        val fibonacci = engine.createFigures(
            instance("fibonacci_retracement", listOf(point(Long.MIN_VALUE, Double.MAX_VALUE), point(Long.MAX_VALUE, -Double.MAX_VALUE))),
        )
        assertTrue(fibonacci.all { figure ->
            val segment = figure as KLineOverlayFigure.Segment
            segment.start.value.isFinite() && segment.end.value.isFinite()
        })

        val channel = engine.createFigures(
            instance(
                "price_channel",
                listOf(
                    point(Long.MAX_VALUE - 4, 10.0),
                    point(Long.MAX_VALUE - 3, 20.0),
                    point(Long.MAX_VALUE - 2, 30.0),
                ),
            ),
        )
        val middle = channel[1] as KLineOverlayFigure.InfiniteLine
        assertEquals(Long.MAX_VALUE - 3, middle.start.timestamp)
        assertEquals(Long.MAX_VALUE - 2, middle.through.timestamp)

        assertFailsWith<IllegalArgumentException> {
            engine.createFigures(
                instance(
                    "parallel_lines",
                    listOf(point(Long.MIN_VALUE, 10.0), point(Long.MAX_VALUE, 20.0), point(0, 30.0)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            engine.createFigures(
                instance(
                    "parallel_lines",
                    listOf(point(1, -Double.MAX_VALUE), point(2, Double.MAX_VALUE), point(3, 0.0)),
                ),
            )
        }
    }

    @Test
    fun axisAlignedPrimitivesDoNotDependOnSyntheticAdjacentMarketCoordinates() {
        val timestamp = 1_000_000L
        val coordinates = KLineXCoordinateSystem(
            plot = KLineRect(0.0, 0.0, 100.0, 100.0),
            viewport = KLineViewport(0.0, 2.0, 10.0, 0.0),
            bars = listOf(marketBar(0), marketBar(timestamp), marketBar(2_000_000)),
        )
        assertEquals(coordinates.timestampToPixel(timestamp), coordinates.timestampToPixel(timestamp + 1))
        val engine = KLineOverlayEngine(KLineBuiltInOverlays.registry())
        val style = KLineOverlayFigureStyle(color = "#445566", lineWidth = 2.0)

        assertEquals(
            KLineOverlayFigure.HorizontalLine(value = 42.0, style = style),
            engine.createFigures(instance("horizontal_line", listOf(point(timestamp, 42.0)), style)).single(),
        )
        assertEquals(
            KLineOverlayFigure.VerticalLine(timestamp = timestamp, style = style),
            engine.createFigures(instance("vertical_line", listOf(point(timestamp, 42.0)), style)).single(),
        )
        assertEquals(
            KLineOverlayFigure.HorizontalLine(value = 42.0, style = style),
            engine.createFigures(instance("price_line", listOf(point(timestamp, 42.0)), style)).first(),
        )
    }

    @Test
    fun priceLineOnlyUsesIntegerTextWhenTheDoubleIsSafelyRepresentableAsLong() {
        val engine = KLineOverlayEngine(KLineBuiltInOverlays.registry())
        val unsafeBoundary = Long.MAX_VALUE.toDouble()

        val ordinaryText = engine.createFigures(instance("price_line", listOf(point(1, 42.0))))[1]
            as KLineOverlayFigure.Text
        val boundaryText = engine.createFigures(instance("price_line", listOf(point(1, unsafeBoundary))))[1]
            as KLineOverlayFigure.Text

        assertEquals("42", ordinaryText.text)
        assertEquals(unsafeBoundary.toString(), boundaryText.text)
        assertNotEquals(Long.MAX_VALUE.toString(), boundaryText.text)
    }

    @Test
    fun directionDependentTemplatesRejectCoincidentControlPoints() {
        val engine = KLineOverlayEngine(KLineBuiltInOverlays.registry())
        val duplicate = point(10, 20.0)

        listOf("trend_line", "ray", "fibonacci_retracement").forEach { templateName ->
            assertFailsWith<IllegalArgumentException> {
                engine.createFigures(instance(templateName, listOf(duplicate, duplicate)))
            }
        }
        listOf("parallel_lines", "price_channel").forEach { templateName ->
            assertFailsWith<IllegalArgumentException> {
                engine.createFigures(instance(templateName, listOf(duplicate, duplicate, point(30, 40.0))))
            }
        }
    }

    @Test
    fun fibonacciRequiresBothTimestampAndValueSpan() {
        val engine = KLineOverlayEngine(KLineBuiltInOverlays.registry())

        assertFailsWith<IllegalArgumentException> {
            engine.createFigures(
                instance("fibonacci_retracement", listOf(point(10, 20.0), point(10, 40.0))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            engine.createFigures(
                instance("fibonacci_retracement", listOf(point(10, 20.0), point(30, 20.0))),
            )
        }
    }

    private fun instance(
        templateName: String,
        points: List<KLineOverlayPoint>,
        style: KLineOverlayFigureStyle = KLineOverlayFigureStyle(),
        extendData: Map<String, String> = emptyMap(),
    ) = KLineOverlayInstance(
        id = "overlay-1",
        templateName = templateName,
        groupId = "analysis",
        paneId = "price",
        points = points,
        visible = true,
        locked = false,
        magnetMode = KLineOverlayMagnetMode.STRONG,
        zIndex = 7,
        styles = mapOf(KLineOverlayStyleKey.DEFAULT to style),
        extendData = extendData,
    )

    private fun point(timestamp: Long, value: Double) = KLineOverlayPoint(timestamp, value)

    private fun marketBar(timestamp: Long) = KLineBar(
        timestamp = timestamp,
        open = 1.0,
        high = 1.0,
        low = 1.0,
        close = 1.0,
    )

    private fun overlayTemplate(templateName: String): KLineOverlayTemplate = object : KLineOverlayTemplate {
        override val name = templateName
        override val requiredPointCount = 1
        override val drawingMode = KLineOverlayDrawingMode.POINT_BY_POINT
        override fun createFigures(context: KLineOverlayContext): List<KLineOverlayFigure> = emptyList()
    }
}
