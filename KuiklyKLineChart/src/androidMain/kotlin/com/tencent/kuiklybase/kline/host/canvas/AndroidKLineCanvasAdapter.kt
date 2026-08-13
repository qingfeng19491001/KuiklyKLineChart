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

    private fun parseColor(color: String): Int = try {
        val raw = color.removePrefix("#")
        when (raw.length) {
            8 -> Color.parseColor("#${raw.takeLast(2)}${raw.take(6)}")
            else -> Color.parseColor("#$raw")
        }
    } catch (_: Throwable) { Color.GRAY }

    private fun Float.dp(): Float = this * density

    override fun withSave(block: () -> Unit) {
        val count = canvas.save()
        try { block() } finally { canvas.restoreToCount(count) }
    }

    override fun drawLine(
        startX: Double, startY: Double, endX: Double, endY: Double,
        color: String, width: Double, dash: List<Double>,
    ) {
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = parseColor(color)
        paint.strokeWidth = width.toFloat().coerceAtLeast(0.5f)
        if (dash.isNotEmpty()) {
            paint.pathEffect = DashPathEffect(dash.map { it.toFloat() }.toFloatArray(), 0f)
        }
        canvas.drawLine(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), paint)
    }

    override fun drawRect(
        left: Double, top: Double, right: Double, bottom: Double,
        color: String, strokeColor: String?, strokeWidth: Double, cornerRadius: Double,
    ) {
        paint.reset(); paint.isAntiAlias = true
        val rectF = android.graphics.RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = parseColor(color)
        if (cornerRadius > 0) {
            canvas.drawRoundRect(rectF, cornerRadius.toFloat(), cornerRadius.toFloat(), paint)
        } else {
            canvas.drawRect(rectF, paint)
        }
        if (strokeColor != null && strokeWidth > 0) {
            paint.style = Paint.Style.STROKE
            paint.color = parseColor(strokeColor)
            paint.strokeWidth = strokeWidth.toFloat()
            if (cornerRadius > 0) canvas.drawRoundRect(rectF, cornerRadius.toFloat(), cornerRadius.toFloat(), paint)
            else canvas.drawRect(rectF, paint)
        }
    }

    override fun drawPolyline(
        points: List<Pair<Double, Double>>, color: String, width: Double, dash: List<Double>,
    ) {
        if (points.size < 2) return
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = parseColor(color)
        paint.strokeWidth = width.toFloat().coerceAtLeast(0.5f)
        if (dash.isNotEmpty()) paint.pathEffect = DashPathEffect(dash.map { it.toFloat() }.toFloatArray(), 0f)
        path.reset()
        points.forEachIndexed { idx, (x, y) ->
            if (idx == 0) path.moveTo(x.toFloat(), y.toFloat())
            else path.lineTo(x.toFloat(), y.toFloat())
        }
        canvas.drawPath(path, paint)
    }

    override fun drawCircle(cx: Double, cy: Double, radius: Double, color: String) {
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = parseColor(color)
        canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius.toFloat().coerceAtLeast(1f), paint)
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
    }

    override fun drawText(
        text: String, left: Double, top: Double, right: Double, bottom: Double,
        color: String, textSize: Double,
    ) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = parseColor(color)
        paint.textSize = textSize.toFloat().coerceAtLeast(8f)
        paint.typeface = Typeface.DEFAULT
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
