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

class OverlayDrawPage : KLineBaseDemoPage() {
    override val title: String = "画线 Overlay 演示"
    override val toolbarActions: List<KLineToolbarAction> = listOf(
        KLineToolbarAction("trend", "📈 趋势线"),
        KLineToolbarAction("hline", "➖ 水平线"),
        KLineToolbarAction("vline", "⏸ 垂直线"),
        KLineToolbarAction("text", "🔤 文本"),
        KLineToolbarAction("delete", "🗑 删除"),
        KLineToolbarAction("cancel", "✖ 取消"),
    )

    private val bars = RandomBarGenerator.defaultSymbolBars(count = 600, seed = 9876L)
    private val dataSource = StaticKLineDataSource(bars)
    private val view: KLineChartView = KLineChartView(dataSource, controller) {
        theme { com.tencent.kuiklybase.kline.config.KLineTheme.LIGHT }
        addDefaultIndicators()
    }

    override fun start() {
        view.attach()
        controller.setMarket(KLineSymbol("00700", "Overlay Demo"), KLinePeriod(1, KLinePeriodUnit.DAY))
    }

    override fun onToolbarAction(actionId: String) {
        when (actionId) {
            "trend" -> controller.beginOverlay("TREND_LINE", "price", magnetMode = com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode.STRONG)
            "hline" -> controller.beginOverlay("HORIZONTAL_LINE", "price", magnetMode = com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode.STRONG)
            "vline" -> controller.beginOverlay("VERTICAL_LINE", "price", magnetMode = com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode.NONE)
            "text" -> controller.beginOverlay("TEXT", "price")
            "delete" -> controller.deleteSelectedOverlay()
            "cancel" -> controller.cancelInteraction()
        }
    }

    override fun onSizeChanged(width: Int, height: Int) {
        super.onSizeChanged(width, height)
        view.onSizeChanged(width, (height - KLineToolbar.HEIGHT).toInt())
    }

    override fun draw(canvas: KLineCanvasAdapter) {
        view.draw(canvas)
        drawToolbar(canvas)
    }

    override fun onPointer(x: Double, y: Double, kind: PointerKind) {
        val hit = hitToolbar(x, y)
        if (hit != null && kind == PointerKind.UP) { onToolbarAction(hit.id); return }
        view.onPointerEvent(IndicatorSwitchPage.pointerEvent(x, y, kind))
    }

    override fun dispose() = view.dispose()
}
