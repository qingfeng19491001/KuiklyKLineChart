package com.kuikly.kuiklyklinechart.shared.demo

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.kuikly.kuiklyklinechart.shared.KLineChartView
import com.kuikly.kuiklyklinechart.shared.canvas.KLineCanvasAdapter
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbar
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbarAction
import com.kuikly.kuiklyklinechart.shared.router.KLineBaseDemoPage
import com.kuikly.kuiklyklinechart.shared.router.PointerKind
import com.tencent.kuiklybase.kline.view.KLineChartView as LibraryKLineChartView

class OverlayDrawPage : KLineBaseDemoPage() {
    override val title: String = "画线 Overlay 演示"
    override val toolbarActions: List<KLineToolbarAction> = listOf(
        KLineToolbarAction("trend", "趋势"),
        KLineToolbarAction("hline", "水平"),
        KLineToolbarAction("hray", "横射"),
        KLineToolbarAction("vline", "垂直"),
        KLineToolbarAction("fib", "斐波"),
        KLineToolbarAction("brush", "画笔"),
        KLineToolbarAction("tag", "标签"),
        KLineToolbarAction("delete", "删除"),
        KLineToolbarAction("cancel", "取消"),
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
            "trend" -> controller.beginOverlay(LibraryKLineChartView.OverlayTemplate.TREND_LINE, "price", magnetMode = KLineOverlayMagnetMode.STRONG)
            "hline" -> controller.beginOverlay(LibraryKLineChartView.OverlayTemplate.HORIZONTAL_LINE, "price", magnetMode = KLineOverlayMagnetMode.STRONG)
            "hray" -> controller.beginOverlay(LibraryKLineChartView.OverlayTemplate.HORIZONTAL_RAY, "price", magnetMode = KLineOverlayMagnetMode.STRONG)
            "vline" -> controller.beginOverlay(LibraryKLineChartView.OverlayTemplate.VERTICAL_LINE, "price", magnetMode = KLineOverlayMagnetMode.NONE)
            "tag" -> controller.beginOverlay(LibraryKLineChartView.OverlayTemplate.SIMPLE_TAG, "price", magnetMode = KLineOverlayMagnetMode.WEAK)
            "fib" -> controller.beginOverlay(LibraryKLineChartView.OverlayTemplate.FIBONACCI_RETRACEMENT, "price", magnetMode = KLineOverlayMagnetMode.WEAK)
            "brush" -> controller.beginOverlay(LibraryKLineChartView.OverlayTemplate.FREEHAND, "price")
            "text" -> controller.beginOverlay(LibraryKLineChartView.OverlayTemplate.TEXT, "price")
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
