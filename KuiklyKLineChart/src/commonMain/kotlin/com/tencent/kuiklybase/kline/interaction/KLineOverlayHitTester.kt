package com.tencent.kuiklybase.kline.interaction

import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.overlay.KLineOverlayEngine
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigure
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.max

enum class KLineOverlayHitType { CONTROL_POINT, FIGURE }

data class KLineOverlayHit(
    val instanceId: String,
    val type: KLineOverlayHitType,
    val pointIndex: Int? = null,
)

class KLineOverlayHitTester(
    private val overlayEngine: KLineOverlayEngine,
) {
    fun hitTest(
        instances: List<KLineOverlayInstance>,
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
        hitRadius: Double = 8.0,
        controlPointRadius: Double = hitRadius,
    ): KLineOverlayHit? {
        if (!pixelX.isFinite() || !pixelY.isFinite() || !hitRadius.isFinite() || hitRadius < 0.0 ||
            !controlPointRadius.isFinite() || controlPointRadius < 0.0) return null
        val ordered = instances.withIndex()
            .filter { it.value.visible }
            .sortedWith(compareByDescending<IndexedValue<KLineOverlayInstance>> { it.value.zIndex }.thenByDescending { it.index })
        ordered.forEach { indexed ->
            indexed.value.points.forEachIndexed { pointIndex, point ->
                if (distance(pixelX, pixelY, point, xCoordinates, yCoordinates) <= controlPointRadius) {
                    return KLineOverlayHit(indexed.value.id, KLineOverlayHitType.CONTROL_POINT, pointIndex)
                }
            }
        }
        ordered.forEach { indexed ->
            val instance = indexed.value
            if (overlayEngine.createFigures(instance).any { figure ->
                    hitsFigure(figure, pixelX, pixelY, xCoordinates, yCoordinates, hitRadius)
                }) {
                return KLineOverlayHit(instance.id, KLineOverlayHitType.FIGURE)
            }
        }
        return null
    }

    companion object {
        private val defaultEngine = KLineOverlayEngine(KLineExtensionRegistry())
        private val defaultTester = KLineOverlayHitTester(defaultEngine)

        fun hitTest(
            instance: KLineOverlayInstance,
            pixelX: Double,
            pixelY: Double,
            xCoordinates: KLineXCoordinateSystem,
            yCoordinates: KLineYCoordinateSystem,
            hitRadius: Double = 8.0,
            controlPointRadius: Double = hitRadius,
        ): KLineOverlayHit? = defaultTester.hitTest(
            listOf(instance), pixelX, pixelY, xCoordinates, yCoordinates, hitRadius, controlPointRadius,
        )
    }

    private fun hitsFigure(
        figure: KLineOverlayFigure,
        x: Double,
        y: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
        radius: Double,
    ): Boolean {
        val tolerance = radius + figure.style.lineWidth / 2.0
        return when (figure) {
            is KLineOverlayFigure.HorizontalLine ->
                kotlin.math.abs(y - yCoordinates.valueToPixel(figure.value)) <= tolerance
            is KLineOverlayFigure.VerticalLine ->
                xCoordinates.timestampToPixel(figure.timestamp)?.let { kotlin.math.abs(x - it) <= tolerance } == true
            is KLineOverlayFigure.Segment -> lineDistance(x, y, pixel(figure.start, xCoordinates, yCoordinates), pixel(figure.end, xCoordinates, yCoordinates), Projection.SEGMENT) <= tolerance
            is KLineOverlayFigure.Ray -> lineDistance(x, y, pixel(figure.start, xCoordinates, yCoordinates), pixel(figure.through, xCoordinates, yCoordinates), Projection.RAY) <= tolerance
            is KLineOverlayFigure.InfiniteLine -> lineDistance(x, y, pixel(figure.start, xCoordinates, yCoordinates), pixel(figure.through, xCoordinates, yCoordinates), Projection.INFINITE) <= tolerance
            is KLineOverlayFigure.Polyline -> figure.points.zipWithNext().any { (start, end) ->
                lineDistance(x, y, pixel(start, xCoordinates, yCoordinates), pixel(end, xCoordinates, yCoordinates), Projection.SEGMENT) <= tolerance
            }
            is KLineOverlayFigure.Text -> distance(x, y, figure.anchor, xCoordinates, yCoordinates) <= radius
        }
    }

    private fun distance(
        x: Double,
        y: Double,
        point: KLineOverlayPoint,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ): Double {
        val pixel = pixel(point, xCoordinates, yCoordinates)
        return hypot(x - pixel.x, y - pixel.y)
    }

    private fun pixel(
        point: KLineOverlayPoint,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
    ) = Pixel(
        x = xCoordinates.timestampToPixel(point.timestamp) ?: Double.POSITIVE_INFINITY,
        y = yCoordinates.valueToPixel(point.value),
    )

    private fun lineDistance(x: Double, y: Double, start: Pixel, end: Pixel, projection: Projection): Double {
        if (start.x == end.x && start.y == end.y) return hypot(x - start.x, y - start.y)
        val scale = max(max(abs(start.x), abs(end.x)), max(abs(start.y), abs(end.y)))
        if (!scale.isFinite() || scale == 0.0) return Double.POSITIVE_INFINITY
        val startX = start.x / scale
        val startY = start.y / scale
        val endX = end.x / scale
        val endY = end.y / scale
        val directionX = endX - startX
        val directionY = endY - startY
        val directionLength = hypot(directionX, directionY)
        val unitX: Double
        val unitY: Double
        val parameter: Double
        if (directionLength > 0.0) {
            unitX = directionX / directionLength
            unitY = directionY / directionLength
            val queryX = x / scale - startX
            val queryY = y / scale - startY
            parameter = (queryX * unitX + queryY * unitY) / directionLength
        } else {
            val directX = end.x - start.x
            val directY = end.y - start.y
            val directScale = max(abs(directX), abs(directY))
            if (!directScale.isFinite() || directScale == 0.0) return Double.POSITIVE_INFINITY
            val normalizedX = directX / directScale
            val normalizedY = directY / directScale
            val normalizedLength = hypot(normalizedX, normalizedY)
            unitX = normalizedX / normalizedLength
            unitY = normalizedY / normalizedLength
            parameter = (
                ((x - start.x) / directScale) * unitX +
                    ((y - start.y) / directScale) * unitY
                ) / normalizedLength
        }
        if (parameter.isNaN()) return Double.POSITIVE_INFINITY
        when (projection) {
            Projection.SEGMENT -> {
                if (parameter <= 0.0) return hypot(x - start.x, y - start.y)
                if (parameter >= 1.0) return hypot(x - end.x, y - end.y)
            }
            Projection.RAY -> if (parameter <= 0.0) return hypot(x - start.x, y - start.y)
            Projection.INFINITE -> Unit
        }
        val queryScale = max(max(abs(x), abs(start.x)), max(abs(y), abs(start.y)))
        if (queryScale == 0.0) return 0.0
        val localX = x / queryScale - start.x / queryScale
        val localY = y / queryScale - start.y / queryScale
        val perpendicular = abs(localX * unitY - localY * unitX)
        return perpendicular * queryScale
    }

    private data class Pixel(val x: Double, val y: Double)
    private enum class Projection { SEGMENT, RAY, INFINITE }
}
