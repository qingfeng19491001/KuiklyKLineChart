package com.tencent.kuiklybase.kline.overlay

import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry

internal object KLineBuiltInOverlays {
    const val HORIZONTAL_LINE_NAME = "horizontal_line"
    const val VERTICAL_LINE_NAME = "vertical_line"
    const val SEGMENT_NAME = "segment"
    const val TREND_LINE_NAME = "trend_line"
    const val RAY_NAME = "ray"
    const val PRICE_LINE_NAME = "price_line"
    const val PARALLEL_LINES_NAME = "parallel_lines"
    const val PRICE_CHANNEL_NAME = "price_channel"
    const val FIBONACCI_RETRACEMENT_NAME = "fibonacci_retracement"
    const val TEXT_ANNOTATION_NAME = "text_annotation"
    const val FREEHAND_NAME = "freehand"

    val HORIZONTAL_LINE = template(HORIZONTAL_LINE_NAME, 1) { context ->
        val point = context.points[0]
        listOf(KLineOverlayFigure.HorizontalLine(point.value, context.style()))
    }
    val VERTICAL_LINE = template(VERTICAL_LINE_NAME, 1) { context ->
        val point = context.points[0]
        listOf(KLineOverlayFigure.VerticalLine(point.timestamp, context.style()))
    }
    val SEGMENT = template(SEGMENT_NAME, 2) { context ->
        listOf(KLineOverlayFigure.Segment(context.points[0], context.points[1], context.style()))
    }
    val TREND_LINE = template(TREND_LINE_NAME, 2) { context ->
        requireDistinctPoints(context.points[0], context.points[1], TREND_LINE_NAME)
        listOf(KLineOverlayFigure.InfiniteLine(context.points[0], context.points[1], context.style()))
    }
    val RAY = template(RAY_NAME, 2) { context ->
        requireDistinctPoints(context.points[0], context.points[1], RAY_NAME)
        listOf(KLineOverlayFigure.Ray(context.points[0], context.points[1], context.style()))
    }
    val PRICE_LINE = template(PRICE_LINE_NAME, 1) { context ->
        val point = context.points[0]
        listOf(
            KLineOverlayFigure.HorizontalLine(point.value, context.style()),
            KLineOverlayFigure.Text(
                point,
                context.extendData["text"] ?: formatPrice(point.value),
                context.style(KLineOverlayStyleKey.TEXT),
            ),
        )
    }
    val PARALLEL_LINES = template(PARALLEL_LINES_NAME, 3) { context ->
        parallelFigures(context, includeMiddle = false)
    }
    val PRICE_CHANNEL = template(PRICE_CHANNEL_NAME, 3) { context ->
        parallelFigures(context, includeMiddle = true)
    }
    val FIBONACCI_RETRACEMENT = template(FIBONACCI_RETRACEMENT_NAME, 2) { context ->
        val start = context.points[0]
        val end = context.points[1]
        requireFibonacciSpan(start, end)
        listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.764, 1.0).map { ratio ->
            val value = interpolateFinite(start.value, end.value, ratio)
            KLineOverlayFigure.Segment(
                KLineOverlayPoint(start.timestamp, value),
                KLineOverlayPoint(end.timestamp, value),
                context.style(),
            )
        }
    }
    val TEXT_ANNOTATION = template(TEXT_ANNOTATION_NAME, 1) { context ->
        listOf(
            KLineOverlayFigure.Text(
                context.points[0],
                context.extendData["text"].orEmpty(),
                context.style(KLineOverlayStyleKey.TEXT),
            ),
        )
    }
    val FREEHAND = template(FREEHAND_NAME, 2, KLineOverlayDrawingMode.CONTINUOUS) { context ->
        listOf(KLineOverlayFigure.Polyline(context.points.toList(), context.style()))
    }

    val templates: List<KLineOverlayTemplate> = listOf(
        HORIZONTAL_LINE,
        VERTICAL_LINE,
        SEGMENT,
        TREND_LINE,
        RAY,
        PRICE_LINE,
        PARALLEL_LINES,
        PRICE_CHANNEL,
        FIBONACCI_RETRACEMENT,
        TEXT_ANNOTATION,
        FREEHAND,
    )

    fun registry(): KLineExtensionRegistry = KLineExtensionRegistry(overlayTemplates = templates)

    private fun template(
        name: String,
        requiredPointCount: Int,
        drawingMode: KLineOverlayDrawingMode = KLineOverlayDrawingMode.POINT_BY_POINT,
        createFigures: (KLineOverlayContext) -> List<KLineOverlayFigure>,
    ): KLineOverlayTemplate = object : KLineOverlayTemplate {
        override val name = name
        override val requiredPointCount = requiredPointCount
        override val drawingMode = drawingMode
        override fun createFigures(context: KLineOverlayContext): List<KLineOverlayFigure> = createFigures(context)
    }

    private fun parallelFigures(
        context: KLineOverlayContext,
        includeMiddle: Boolean,
    ): List<KLineOverlayFigure> {
        val first = context.points[0]
        val second = context.points[1]
        val parallelStart = context.points[2]
        requireDistinctPoints(first, second, context.instance.templateName)
        val timestampDelta = subtractExact(second.timestamp, first.timestamp)
        val valueDelta = second.value - first.value
        require(valueDelta.isFinite()) { "Overlay parallel-line value delta is not representable" }
        val parallelEnd = KLineOverlayPoint(
            timestamp = addExact(parallelStart.timestamp, timestampDelta),
            value = (parallelStart.value + valueDelta).also { value ->
                require(value.isFinite()) { "Overlay parallel-line translated value is not representable" }
            },
        )
        val result = mutableListOf<KLineOverlayFigure>(
            KLineOverlayFigure.InfiniteLine(first, second, context.style()),
        )
        if (includeMiddle) {
            result += KLineOverlayFigure.InfiniteLine(
                KLineOverlayPoint(midpoint(first.timestamp, parallelStart.timestamp), midpoint(first.value, parallelStart.value)),
                KLineOverlayPoint(midpoint(second.timestamp, parallelEnd.timestamp), midpoint(second.value, parallelEnd.value)),
                context.style(),
            )
        }
        result += KLineOverlayFigure.InfiniteLine(parallelStart, parallelEnd, context.style())
        return result
    }

    private fun addExact(left: Long, right: Long): Long {
        require(right <= 0 || left <= Long.MAX_VALUE - right) { "Overlay timestamp addition overflow" }
        require(right >= 0 || left >= Long.MIN_VALUE - right) { "Overlay timestamp addition overflow" }
        return left + right
    }

    private fun requireDistinctPoints(
        first: KLineOverlayPoint,
        second: KLineOverlayPoint,
        templateName: String,
    ) {
        require(first.timestamp != second.timestamp || first.value != second.value) {
            "Overlay $templateName requires distinct direction points"
        }
    }

    private fun requireFibonacciSpan(
        start: KLineOverlayPoint,
        end: KLineOverlayPoint,
    ) {
        require(start.timestamp != end.timestamp) {
            "Overlay $FIBONACCI_RETRACEMENT_NAME requires distinct timestamps"
        }
        require(start.value != end.value) {
            "Overlay $FIBONACCI_RETRACEMENT_NAME requires distinct values"
        }
    }

    private fun subtractExact(left: Long, right: Long): Long {
        require(right <= 0 || left >= Long.MIN_VALUE + right) { "Overlay timestamp subtraction overflow" }
        require(right >= 0 || left <= Long.MAX_VALUE + right) { "Overlay timestamp subtraction overflow" }
        return left - right
    }

    private fun midpoint(first: Long, second: Long): Long =
        (first and second) + ((first xor second) shr 1)

    private fun midpoint(first: Double, second: Double): Double {
        val delta = second - first
        val result = if (delta.isFinite()) first + delta / 2.0 else first / 2.0 + second / 2.0
        require(result.isFinite()) { "Overlay midpoint value is not representable" }
        return result
    }

    private fun interpolateFinite(
        start: Double,
        end: Double,
        ratio: Double,
    ): Double {
        if (ratio == 0.0) return start
        if (ratio == 1.0) return end
        if (start == end) return start
        val value = if ((start < 0.0) == (end < 0.0)) {
            start + (end - start) * ratio
        } else {
            start * (1.0 - ratio) + end * ratio
        }
        require(value.isFinite()) { "Overlay Fibonacci value is not representable" }
        return value
    }

    private fun formatPrice(value: Double): String {
        val withinSafeLongBounds = value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()
        if (value % 1.0 == 0.0 && withinSafeLongBounds) {
            val integer = value.toLong()
            if (integer.toDouble() == value) return integer.toString()
        }
        return value.toString()
    }
}
