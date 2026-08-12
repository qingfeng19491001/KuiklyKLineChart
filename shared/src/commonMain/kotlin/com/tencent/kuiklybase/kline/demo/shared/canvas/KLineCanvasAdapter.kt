package com.tencent.kuiklybase.kline.demo.shared.canvas

import com.tencent.kuiklybase.kline.render.KLineDrawingPrimitive
import com.tencent.kuiklybase.kline.render.KLinePrimitiveSink
import com.tencent.kuiklybase.kline.render.KLineRenderPlan
import com.tencent.kuiklybase.kline.render.KLineRenderPipeline

interface KLineCanvasAdapter {
    fun withSave(block: () -> Unit)
    fun drawLine(startX: Double, startY: Double, endX: Double, endY: Double, color: String, width: Double, dash: List<Double> = emptyList())
    fun drawRect(left: Double, top: Double, right: Double, bottom: Double, color: String, strokeColor: String? = null, strokeWidth: Double = 0.0, cornerRadius: Double = 0.0)
    fun drawPolyline(points: List<Pair<Double, Double>>, color: String, width: Double, dash: List<Double> = emptyList())
    fun drawCircle(cx: Double, cy: Double, radius: Double, color: String)
    fun drawCandle(x: Double, openY: Double, highY: Double, lowY: Double, closeY: Double, bodyWidth: Double, color: String, wickWidth: Double)
    fun drawText(text: String, left: Double, top: Double, right: Double, bottom: Double, color: String, textSize: Double)
}

object KLineCanvasRenderer {
    private val pipeline = KLineRenderPipeline()

    fun render(plan: KLineRenderPlan, canvas: KLineCanvasAdapter) {
        val sink = object : KLinePrimitiveSink {
            override fun draw(primitive: KLineDrawingPrimitive) {
                when (primitive) {
                    is KLineDrawingPrimitive.Line -> canvas.drawLine(
                        primitive.start.x, primitive.start.y,
                        primitive.end.x, primitive.end.y,
                        primitive.stroke.color, primitive.stroke.width, primitive.stroke.dash,
                    )
                    is KLineDrawingPrimitive.Rect -> canvas.drawRect(
                        primitive.bounds.left, primitive.bounds.top,
                        primitive.bounds.right, primitive.bounds.bottom,
                        primitive.fillColor,
                        primitive.stroke?.color, primitive.stroke?.width ?: 0.0,
                        primitive.cornerRadius,
                    )
                    is KLineDrawingPrimitive.Polyline -> canvas.drawPolyline(
                        primitive.points.map { it.x to it.y },
                        primitive.stroke.color, primitive.stroke.width, primitive.stroke.dash,
                    )
                    is KLineDrawingPrimitive.Circle -> canvas.drawCircle(
                        primitive.center.x, primitive.center.y, primitive.radius, primitive.color,
                    )
                    is KLineDrawingPrimitive.Candle -> canvas.drawCandle(
                        primitive.x, primitive.openY, primitive.highY, primitive.lowY, primitive.closeY,
                        primitive.bodyWidth, primitive.color, primitive.wickWidth,
                    )
                    is KLineDrawingPrimitive.Text -> canvas.drawText(
                        primitive.text,
                        primitive.bounds.left, primitive.bounds.top,
                        primitive.bounds.right, primitive.bounds.bottom,
                        primitive.color, primitive.textSize,
                    )
                }
            }
        }
        pipeline.render(plan, sink)
    }
}
