package com.kuikly.kuiklyklinechart.shared.canvas

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.views.CanvasContext
import kotlin.math.PI

/**
 * 将 K-Line 渲染计划通过 Kuikly 的 [CanvasContext]（HTML5 Canvas 风格 API）绘制到屏幕。
 *
 * 该适配器让 commonMain 中的 K 线渲染逻辑无需依赖任何平台 Canvas 类型，
 * 仅通过 Kuikly [CanvasContext] 即可在 Android / iOS / 鸿蒙 / H5 / 小程序全端绘制。
 */
class KuiklyKLineCanvasAdapter(private val ctx: CanvasContext) : KLineCanvasAdapter {

    override fun withSave(block: () -> Unit) {
        ctx.save()
        try {
            block()
        } finally {
            ctx.restore()
        }
    }

    override fun drawLine(
        startX: Double, startY: Double,
        endX: Double, endY: Double,
        color: String, width: Double,
        dash: List<Double>,
    ) {
        ctx.beginPath()
        ctx.moveTo(startX.toFloat(), startY.toFloat())
        ctx.lineTo(endX.toFloat(), endY.toFloat())
        ctx.strokeStyle(parseColor(color))
        ctx.lineWidth(width.toFloat())
        if (dash.isNotEmpty()) {
            ctx.setLineDash(dash.map { it.toFloat() })
        } else {
            ctx.setLineDash(emptyList())
        }
        ctx.stroke()
    }

    override fun drawRect(
        left: Double, top: Double,
        right: Double, bottom: Double,
        color: String,
        strokeColor: String?,
        strokeWidth: Double,
        cornerRadius: Double,
    ) {
        val l = left.toFloat(); val t = top.toFloat()
        val r = right.toFloat(); val b = bottom.toFloat()
        ctx.fillStyle(parseColor(color))
        ctx.beginPath()
        val radius = cornerRadius.toFloat().coerceIn(0f, minOf(r - l, b - t) / 2f)
        if (radius > 0f) {
            ctx.moveTo(l + radius, t)
            ctx.lineTo(r - radius, t)
            ctx.quadraticCurveTo(r, t, r, t + radius)
            ctx.lineTo(r, b - radius)
            ctx.quadraticCurveTo(r, b, r - radius, b)
            ctx.lineTo(l + radius, b)
            ctx.quadraticCurveTo(l, b, l, b - radius)
            ctx.lineTo(l, t + radius)
            ctx.quadraticCurveTo(l, t, l + radius, t)
        } else {
            ctx.moveTo(l, t)
            ctx.lineTo(r, t)
            ctx.lineTo(r, b)
            ctx.lineTo(l, b)
        }
        ctx.closePath()
        ctx.fill()
        if (strokeColor != null && strokeWidth > 0.0) {
            ctx.strokeStyle(parseColor(strokeColor))
            ctx.lineWidth(strokeWidth.toFloat())
            ctx.stroke()
        }
    }

    override fun drawPolyline(
        points: List<Pair<Double, Double>>,
        color: String, width: Double,
        dash: List<Double>,
    ) {
        if (points.size < 2) return
        ctx.beginPath()
        val first = points.first()
        ctx.moveTo(first.first.toFloat(), first.second.toFloat())
        for (i in 1 until points.size) {
            ctx.moveTo(points[i - 1].first.toFloat(), points[i - 1].second.toFloat())
            ctx.lineTo(points[i].first.toFloat(), points[i].second.toFloat())
        }
        ctx.strokeStyle(parseColor(color))
        ctx.lineWidth(width.toFloat())
        if (dash.isNotEmpty()) {
            ctx.setLineDash(dash.map { it.toFloat() })
        } else {
            ctx.setLineDash(emptyList())
        }
        ctx.stroke()
    }

    override fun drawCircle(cx: Double, cy: Double, radius: Double, color: String) {
        ctx.beginPath()
        ctx.arc(
            centerX = cx.toFloat(),
            centerY = cy.toFloat(),
            radius = radius.toFloat(),
            startAngle = 0f,
            endAngle = (2f * PI.toFloat()),
            counterclockwise = false,
        )
        ctx.fillStyle(parseColor(color))
        ctx.fill()
    }

    override fun drawCandle(
        x: Double,
        openY: Double, highY: Double, lowY: Double, closeY: Double,
        bodyWidth: Double, color: String, wickWidth: Double,
    ) {
        // 上下影线
        ctx.beginPath()
        ctx.moveTo(x.toFloat(), highY.toFloat())
        ctx.lineTo(x.toFloat(), lowY.toFloat())
        ctx.strokeStyle(parseColor(color))
        ctx.lineWidth(wickWidth.toFloat())
        ctx.stroke()
        // 实体
        val bodyTop = minOf(openY, closeY)
        val bodyBottom = maxOf(openY, closeY)
        val bodyHeight = (bodyBottom - bodyTop).coerceAtLeast(wickWidth)
        val left = (x - bodyWidth / 2).toFloat()
        val top = bodyTop.toFloat()
        val right = (x + bodyWidth / 2).toFloat()
        val bottom = (bodyTop + bodyHeight).toFloat()
        ctx.fillStyle(parseColor(color))
        ctx.beginPath()
        ctx.moveTo(left, top)
        ctx.lineTo(right, top)
        ctx.lineTo(right, bottom)
        ctx.lineTo(left, bottom)
        ctx.closePath()
        ctx.fill()
    }

    override fun drawText(
        text: String,
        left: Double, top: Double,
        right: Double, bottom: Double,
        color: String, textSize: Double,
    ) {
        ctx.fillStyle(parseColor(color))
        ctx.font(size = textSize.toFloat())
        val metrics = ctx.measureText(text)
        val availableWidth = (right - left).toFloat().coerceAtLeast(0f)
        val availableHeight = (bottom - top).toFloat().coerceAtLeast(0f)
        val textHeight = metrics.actualBoundingBoxAscent + metrics.actualBoundingBoxDescent
        val x = left.toFloat() + (availableWidth - metrics.width).coerceAtLeast(0f)
        val baseline = top.toFloat() + (availableHeight - textHeight) / 2f + metrics.actualBoundingBoxAscent
        ctx.fillText(text, x, baseline)
    }

    private fun parseColor(color: String): Color {
        val isArgb = color.startsWith("0x") || color.startsWith("0X")
        val hex = color.removePrefix("#").removePrefix("0x").removePrefix("0X")
        val argb = when (hex.length) {
            3 -> "FF" + hex.map { c -> "$c$c" }.joinToString("")
            6 -> "FF$hex"
            8 -> if (isArgb) hex else hex.takeLast(2) + hex.take(6)
            else -> "FF000000"
        }
        return Color(argb.toLong(16))
    }
}
