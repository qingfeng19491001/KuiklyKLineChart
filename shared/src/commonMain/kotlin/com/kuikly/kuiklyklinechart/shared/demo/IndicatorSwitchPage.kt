package com.kuikly.kuiklyklinechart.shared.demo

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.kuikly.kuiklyklinechart.shared.KLineChartDsl
import com.kuikly.kuiklyklinechart.shared.KLineChartView
import com.kuikly.kuiklyklinechart.shared.canvas.KLineCanvasAdapter
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbar
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbarAction
import com.kuikly.kuiklyklinechart.shared.router.KLineBaseDemoPage
import com.kuikly.kuiklyklinechart.shared.router.PointerKind
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.axis.KLineYAxis

class IndicatorSwitchPage : KLineBaseDemoPage() {
    override val title: String = "指标切换演示"
    override val toolbarActions: List<KLineToolbarAction> = listOf(
        KLineToolbarAction("main_ma", "MA"),
        KLineToolbarAction("main_boll", "BOLL"),
        KLineToolbarAction("main_expma", "EXPMA"),
        KLineToolbarAction("sub_vol", "VOL"),
        KLineToolbarAction("sub_macd", "MACD"),
        KLineToolbarAction("sub_kdj", "KDJ"),
    )

    private val bars = RandomBarGenerator.defaultSymbolBars(count = 600)
    private val dataSource = StaticKLineDataSource(bars)
    private val view: KLineChartView
    private var mainIndicator = "MA"
    private var subIndicator = "VOL"
    private val subPaneId = "sub"

    init {
        val pricePane = KLinePane("price", KLinePaneKind.PRICE, 0, 3.0, 120.0, yAxes = listOf(KLineYAxis("py", 0.0, 100.0, autoScale = true)))
        val subPane = KLinePane(subPaneId, KLinePaneKind.INDICATOR, 1, 1.2, 60.0, yAxes = listOf(KLineYAxis("sy", 0.0, 100.0, autoScale = true)))
        view = KLineChartView(dataSource, controller) {
            pane(pricePane); pane(subPane)
            theme { com.tencent.kuiklybase.kline.config.KLineTheme.LIGHT }
            applyMainIndicator(this, "MA")
            applySubIndicator(this, "VOL")
        }
    }

    private fun applyMainIndicator(dsl: KLineChartDsl, name: String) {
        dsl.indicator(when (name) {
            "BOLL" -> KLineBuiltInIndicators.BOLL.instance("boll-$name", "price", listOf(20.0, 2.0))
            "EXPMA" -> KLineBuiltInIndicators.EXPMA.instance("exp-$name", "price", listOf(12.0))
            else -> KLineBuiltInIndicators.MA.instance("ma5", "price", listOf(5.0))
        })
        if (name == "MA") {
            dsl.indicator(KLineBuiltInIndicators.MA.instance("ma10", "price", listOf(10.0)))
            dsl.indicator(KLineBuiltInIndicators.MA.instance("ma20", "price", listOf(20.0)))
        }
    }

    private fun applySubIndicator(dsl: KLineChartDsl, name: String) {
        dsl.indicator(when (name) {
            "MACD" -> KLineBuiltInIndicators.MACD.instance("macd", subPaneId, listOf(12.0, 26.0, 9.0))
            "KDJ" -> KLineBuiltInIndicators.KDJ.instance("kdj", subPaneId, listOf(9.0, 3.0, 3.0))
            else -> KLineBuiltInIndicators.VOLUME.instance("vol", subPaneId, emptyList())
        })
    }

    override fun start() {
        view.attach()
        controller.setMarket(KLineSymbol("600519", "Demo"), KLinePeriod(1, KLinePeriodUnit.DAY))
    }

    override fun onToolbarAction(actionId: String) {
        when (actionId) {
            "main_ma", "main_boll", "main_expma" -> {
                val next = actionId.substringAfter("main_").uppercase()
                listOf("ma5", "ma10", "ma20", "boll-MA", "boll-BOLL", "boll-EXPMA", "exp-MA", "exp-BOLL", "exp-EXPMA")
                    .forEach { controller.removeIndicator(it) }
                val dsl = KLineChartDsl()
                applyMainIndicator(dsl, next)
                dsl.indicators.forEach { controller.addIndicator(it) }
                mainIndicator = next
            }
            "sub_vol", "sub_macd", "sub_kdj" -> {
                val next = actionId.substringAfter("sub_").uppercase()
                controller.removeIndicator("vol"); controller.removeIndicator("macd"); controller.removeIndicator("kdj")
                val dsl = KLineChartDsl()
                applySubIndicator(dsl, next)
                dsl.indicators.forEach { controller.addIndicator(it) }
                subIndicator = next
            }
        }
    }

    override fun onSizeChanged(width: Int, height: Int) {
        super.onSizeChanged(width, height)
        view.onSizeChanged(width, (height - KLineToolbar.HEIGHT).toInt())
    }

    override fun draw(canvas: KLineCanvasAdapter) {
        view.draw(canvas)
        val selected = buildSet {
            add("main_${mainIndicator.lowercase()}")
            add("sub_${subIndicator.lowercase()}")
        }
        drawToolbar(canvas, selected)
    }

    override fun onPointer(x: Double, y: Double, kind: PointerKind) {
        val hit = hitToolbar(x, y)
        if (hit != null && kind == PointerKind.UP) { onToolbarAction(hit.id); return }
        view.onPointerEvent(pointerEvent(x, y, kind))
    }

    override fun dispose() = view.dispose()

    companion object {
        internal fun pointerEvent(x: Double, y: Double, kind: PointerKind): com.tencent.kuiklybase.kline.KLinePointerEvent = when (kind) {
            PointerKind.DOWN -> com.tencent.kuiklybase.kline.KLinePointerEvent.Down(x, y)
            PointerKind.MOVE -> com.tencent.kuiklybase.kline.KLinePointerEvent.Move(x, y)
            PointerKind.UP -> com.tencent.kuiklybase.kline.KLinePointerEvent.Up(x, y)
            PointerKind.CANCEL -> com.tencent.kuiklybase.kline.KLinePointerEvent.Cancel(x, y)
        }
    }
}
