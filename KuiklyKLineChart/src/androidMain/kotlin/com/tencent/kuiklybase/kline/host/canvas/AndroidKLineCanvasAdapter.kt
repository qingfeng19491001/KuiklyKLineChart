package com.tencent.kuiklybase.kline.host.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface

class AndroidKLineCanvasAdapter(private val canvas: Canvas, private val density: Float = 1f) : KLineCanvasAdapter {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path: Path = Path()

    // A/B 测试（C 组 batchDraw）：画笔状态签名缓存。
    // 批量渲染路径下同样式图元连续输出，签名相同则跳过 Paint 重配置；
    // 默认路径下签名几乎每次不同，行为与全量重配一致。
    private var paintSignature: String? = null

    // 颜色解析缓存，避免每图元一次字符串处理
    private val colorCache = HashMap<String, Int>()

    private fun parseColor(color: String): Int = colorCache.getOrPut(color) {
        try {
            val raw = color.removePrefix("#")
            when (raw.length) {
                8 -> Color.parseColor("#${raw.takeLast(2)}${raw.take(6)}")
                else -> Color.parseColor("#$raw")
            }
        } catch (_: Throwable) { Color.GRAY }
    }

    private fun Float.dp(): Float = this * density

    /** 配置描边画笔；与上次签名一致时直接复用当前状态。 */
    private fun strokePaint(color: String, strokeWidth: Float, dash: List<Double>): Paint {
        val signature = "S|$color|$strokeWidth|$dash"
        if (signature == paintSignature) return paint
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = parseColor(color)
        paint.strokeWidth = strokeWidth
        if (dash.isNotEmpty()) {
            paint.pathEffect = DashPathEffect(dash.map { it.toFloat() }.toFloatArray(), 0f)
        }
        paintSignature = signature
        return paint
    }

    /** 配置填充画笔；与上次签名一致时直接复用当前状态。 */
    private fun fillPaint(color: String): Paint {
        val signature = "F|$color"
        if (signature == paintSignature) return paint
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = parseColor(color)
        paintSignature = signature
        return paint
    }

    override fun withSave(block: () -> Unit) {
        val count = canvas.save()
        try { block() } finally { canvas.restoreToCount(count) }
    }

    override fun drawLine(
        startX: Double, startY: Double, endX: Double, endY: Double,
        color: String, width: Double, dash: List<Double>,
    ) {
        val p = strokePaint(color, width.toFloat().coerceAtLeast(0.5f), dash)
        canvas.drawLine(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), p)
    }

    override fun drawRect(
        left: Double, top: Double, right: Double, bottom: Double,
        color: String, strokeColor: String?, strokeWidth: Double, cornerRadius: Double,
    ) {
        val rectF = android.graphics.RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val p = fillPaint(color)
        if (cornerRadius > 0) {
            canvas.drawRoundRect(rectF, cornerRadius.toFloat(), cornerRadius.toFloat(), p)
        } else {
            canvas.drawRect(rectF, p)
        }
        if (strokeColor != null && strokeWidth > 0) {
            val sp = strokePaint(strokeColor, strokeWidth.toFloat(), emptyList())
            if (cornerRadius > 0) canvas.drawRoundRect(rectF, cornerRadius.toFloat(), cornerRadius.toFloat(), sp)
            else canvas.drawRect(rectF, sp)
        }
    }

    override fun drawPolyline(
        points: List<Pair<Double, Double>>, color: String, width: Double, dash: List<Double>,
    ) {
        if (points.size < 2) return
        val p = strokePaint(color, width.toFloat().coerceAtLeast(0.5f), dash)
        path.reset()
        points.forEachIndexed { idx, (x, y) ->
            if (idx == 0) path.moveTo(x.toFloat(), y.toFloat())
            else path.lineTo(x.toFloat(), y.toFloat())
        }
        canvas.drawPath(path, p)
    }

    override fun drawCircle(cx: Double, cy: Double, radius: Double, color: String) {
        val p = fillPaint(color)
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius.toFloat().coerceAtLeast(1f), p)
    }

    override fun drawCandle(
        x: Double, openY: Double, highY: Double, lowY: Double, closeY: Double,
        bodyWidth: Double, color: String, wickWidth: Double,
    ) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = parseColor(color)
        // wick
        paint.strokeWidth = wickWidth.toFloat().coerceAtLeast(0.5f)
        paint.style = Paint.Style.STROKE
        canvas.drawLine(x.toFloat(), highY.toFloat(), x.toFloat(), lowY.toFloat(), paint)
        // body
        paint.style = Paint.Style.FILL
        val halfBody = (bodyWidth / 2).toFloat()
        val top = minOf(openY.toFloat(), closeY.toFloat())
        val bottom = maxOf(openY.toFloat(), closeY.toFloat())
        val bodyBottom = if (bottom - top < 1f) top + 1f else bottom
        canvas.drawRect(x.toFloat() - halfBody, top, x.toFloat() + halfBody, bodyBottom, paint)
        // 画笔当前处于 FILL(color) 状态，登记签名供后续复用
        paintSignature = "F|$color"
    }

    override fun drawText(
        text: String, left: Double, top: Double, right: Double, bottom: Double,
        color: String, textSize: Double,
    ) {
        // 文本绘制独占配置 textSize/typeface，登记专用签名
        val signature = "T|$color|$textSize"
        if (signature != paintSignature) {
            paint.reset(); paint.isAntiAlias = true
            paint.color = parseColor(color)
            paint.textSize = textSize.toFloat().coerceAtLeast(8f)
            paint.typeface = Typeface.DEFAULT
            paintSignature = signature
        }
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textWidth = bounds.width().toFloat()
        val textHeight = bounds.height().toFloat()
        val availWidth = (right - left).toFloat()
        val availHeight = (bottom - top).toFloat()
        val drawX = left.toFloat() + (availWidth - textWidth).coerceAtLeast(0f)
        val baseline = top.toFloat() + (availHeight + textHeight) / 2f - bounds.bottom.toFloat() + textHeight / 2f
        canvas.drawText(text, drawX, baseline, paint)
    }
}
