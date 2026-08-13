package com.kuikly.kuiklyklinechart.shared.demo

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.kuikly.kuiklyklinechart.shared.KLineChartDsl
import com.kuikly.kuiklyklinechart.shared.KLineChartView
import com.kuikly.kuiklyklinechart.shared.canvas.KLineCanvasAdapter

class BasicKLinePage(
    private val symbol: KLineSymbol = KLineSymbol(ticker = "00700", name = "Demo Stock"),
    private val period: KLinePeriod = KLinePeriod(1, KLinePeriodUnit.DAY),
    private val controller: KLineChartController = KLineChartController(),
) {
    private val bars = RandomBarGenerator.defaultSymbolBars(count = 600, seed = symbol.ticker.hashCode().toLong())
    private val dataSource = StaticKLineDataSource(bars)

    val view: KLineChartView = KLineChartView(
        dataSource = dataSource,
        controller = controller,
    ) {
        theme { com.tencent.kuiklybase.kline.config.KLineTheme.LIGHT }
        addDefaultIndicators()
        onError { println("KLine error: ${it.code} ${it.message}") }
    }

    fun start() {
        view.attach()
        controller.setMarket(symbol, period)
    }

    fun draw(canvas: KLineCanvasAdapter) = view.draw(canvas)
    fun onSize(width: Int, height: Int) = view.onSizeChanged(width, height)

    fun dispose() = view.dispose()
}
