package com.tencent.kuiklybase.kline.demo.shared.demo

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.demo.shared.KLineChartView
import com.tencent.kuiklybase.kline.demo.shared.canvas.KLineCanvasAdapter
import com.tencent.kuiklybase.kline.demo.shared.canvas.KLineToolbar
import com.tencent.kuiklybase.kline.demo.shared.canvas.KLineToolbarAction
import com.tencent.kuiklybase.kline.demo.shared.router.KLineBaseDemoPage
import com.tencent.kuiklybase.kline.demo.shared.router.PointerKind

class InteractionShowcasePage : KLineBaseDemoPage() {
    override val title: String = "交互优先级演示"
    override val toolbarActions: List<KLineToolbarAction> = listOf(
        KLineToolbarAction("overlay", "🔒 画线优先"),
        KLineToolbarAction("crosshair", "🎯 十字光标"),
        KLineToolbarAction("zoom", "🔎 缩放测试"),
        KLineToolbarAction("reset", "⟲ 重置"),
    )

    private val bars = RandomBarGenerator.defaultSymbolBars(count = 800, seed = 7777L)
    private val dataSource = StaticKLineDataSource(bars)
    private val view: KLineChartView = KLineChartView(dataSource, controller) {
        theme { com.tencent.kuiklybase.kline.config.KLineTheme.LIGHT }
        addDefaultIndicators()
    }

    override fun start() {
        view.attach()
        controller.setMarket(KLineSymbol("INTERACT", "交互演示"), KLinePeriod(1, KLinePeriodUnit.DAY))
    }

    override fun onToolbarAction(actionId: String) {
        when (actionId) {
            "overlay" -> {
                controller.beginOverlay("TREND_LINE", "price", magnetMode = com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode.STRONG)
            }
            "crosshair" -> {
                controller.clearCrosshair()
            }
            "zoom" -> {
                controller.zoom(1.5)
            }
            "reset" -> {
                controller.resetViewport()
                controller.cancelInteraction()
            }
        }
    }

    override fun onSizeChanged(width: Int, height: Int) {
        super.onSizeChanged(width, height)
        view.onSizeChanged(width, (height - KLineToolbar.HEIGHT).toInt())
    }

    override fun draw(canvas: KLineCanvasAdapter) {
        view.draw(canvas)
        val header = listOf(
            "手势优先级：画线控制点 > 画线整体 > 十字光标 > 分隔条 > 缩放 > 平移 > 点击",
        )
        var y = 8.0
        header.forEach { line ->
            canvas.drawRect(8.0, y, viewportWidth - 8.0, y + 26.0, "#EAF4FFCC", strokeColor = null, cornerRadius = 4.0)
            canvas.drawText(line, 14.0, y + 5.0, viewportWidth - 14.0, y + 24.0, "#0D47A1FF", 13.0)
            y += 32.0
        }
        drawToolbar(canvas)
    }

    override fun onPointer(x: Double, y: Double, kind: PointerKind) {
        val hit = hitToolbar(x, y)
        if (hit != null && kind == PointerKind.UP) { onToolbarAction(hit.id); return }
        view.onPointerEvent(IndicatorSwitchPage.pointerEvent(x, y, kind))
    }

    override fun dispose() = view.dispose()
}
