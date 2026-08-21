package com.tencent.kuiklybase.kline.host.canvas

import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.render.KLineDrawingPrimitive
import com.tencent.kuiklybase.kline.render.KLinePoint
import com.tencent.kuiklybase.kline.render.KLinePrimitiveSink
import com.tencent.kuiklybase.kline.render.KLinePrimitiveListSink
import com.tencent.kuiklybase.kline.render.KLineRenderLayer
import com.tencent.kuiklybase.kline.render.KLineRenderPlan
import com.tencent.kuiklybase.kline.render.KLineRenderPipeline
import com.tencent.kuiklybase.kline.render.KLineStroke

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
                emitPrimitive(primitive, canvas)
            }
        }
        pipeline.render(plan, sink)
    }

    /**
     * 批量渲染路径（C 组 batchDraw A/B 测试）。
     *
     * 与 [render] 输出完全相同的图元集合，但按「渲染层 → 类型+样式」稳定分组后输出：
     * - 层顺序严格保持（GRID → CANDLE → ... → TOOLTIP），跨层遮挡关系不变；
     * - 同层内相同样式的图元连续输出，适配器可复用同一画笔状态，大幅减少状态切换；
     * - Candle 图元拆分为影线（drawLine）与实体（drawRect），同色蜡烛共享批量绘制。
     */
    fun renderBatched(plan: KLineRenderPlan, canvas: KLineCanvasAdapter) {
        val collected = KLinePrimitiveListSink()
        pipeline.render(plan, collected)
        val flattened = flattenCandles(collected.primitives)
        KLineRenderLayer.entries.sortedBy { it.order }.forEach { layer ->
            val groups = LinkedHashMap<String, MutableList<KLineDrawingPrimitive>>()
            flattened.asSequence().filter { it.layer == layer }.forEach { primitive ->
                groups.getOrPut(styleKey(primitive)) { mutableListOf() }.add(primitive)
            }
            groups.values.forEach { group -> group.forEach { emitPrimitive(it, canvas) } }
        }
    }

    /** 将 Candle 拆分为影线 Line 与实体 Rect，与适配器的 drawCandle 逐根绘制结果一致。 */
    private fun flattenCandles(primitives: List<KLineDrawingPrimitive>): List<KLineDrawingPrimitive> {
        var candleCount = 0
        primitives.forEach { if (it is KLineDrawingPrimitive.Candle) candleCount++ }
        if (candleCount == 0) return primitives
        val result = ArrayList<KLineDrawingPrimitive>(primitives.size + candleCount)
        primitives.forEach { primitive ->
            if (primitive is KLineDrawingPrimitive.Candle) {
                val top = minOf(primitive.openY, primitive.closeY)
                val rawBottom = maxOf(primitive.openY, primitive.closeY)
                val bottom = if (rawBottom - top < 1.0) top + 1.0 else rawBottom
                val halfBody = primitive.bodyWidth / 2.0
                result.add(
                    KLineDrawingPrimitive.Line(
                        primitive.layer,
                        KLinePoint(primitive.x, primitive.highY),
                        KLinePoint(primitive.x, primitive.lowY),
                        KLineStroke(primitive.color, primitive.wickWidth),
                    ),
                )
                result.add(
                    KLineDrawingPrimitive.Rect(
                        primitive.layer,
                        KLineRect(primitive.x - halfBody, top, primitive.x + halfBody, bottom),
                        primitive.color,
                    ),
                )
            } else {
                result.add(primitive)
            }
        }
        return result
    }

    /** 图元的样式签名：同层同签名 → 同画笔状态，可安全连续绘制。 */
    private fun styleKey(primitive: KLineDrawingPrimitive): String = when (primitive) {
        is KLineDrawingPrimitive.Line -> "L|${primitive.stroke.color}|${primitive.stroke.width}|${primitive.stroke.dash}"
        is KLineDrawingPrimitive.Rect -> "R|${primitive.fillColor}|${primitive.stroke?.color}|${primitive.stroke?.width}|${primitive.cornerRadius}"
        is KLineDrawingPrimitive.Polyline -> "P|${primitive.stroke.color}|${primitive.stroke.width}|${primitive.stroke.dash}"
        is KLineDrawingPrimitive.Circle -> "C|${primitive.color}"
        is KLineDrawingPrimitive.Text -> "T|${primitive.color}|${primitive.textSize}"
        is KLineDrawingPrimitive.Candle -> "K|${primitive.color}|${primitive.wickWidth}"
    }

    private fun emitPrimitive(primitive: KLineDrawingPrimitive, canvas: KLineCanvasAdapter) {
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
