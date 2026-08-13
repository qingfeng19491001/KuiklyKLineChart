package com.tencent.kuiklybase.kline

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.signal.KLineSignal
import com.tencent.kuiklybase.kline.signal.KLineSignalType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KLineChartEngineTest {
    @Test
    fun modeAndSignalsAreExposedThroughTheEngineSeam() {
        val engine = KLineChartEngine(dataSource = StaticKLineDataSource(emptyList()))
        val signal = KLineSignal("buy", 1L, 10.0, KLineSignalType.BUY, "Buy", "Reason")

        engine.setMode(KLineChartMode.COMPACT)
        engine.setSignals(listOf(signal))

        val plan = engine.latestRenderPlan(com.tencent.kuiklybase.kline.layout.KLineRect(0.0, 0.0, 100.0, 100.0))
        assertFalse(plan.features.interaction)
        assertEquals(KLinePointerDispatchOutcome.Ignored, engine.dispatchPointerEvent(KLinePointerEvent.Tap(10.0, 10.0)))
        engine.dispose()
    }

    @Test
    fun signalRendersAndTapReturnsTheSameSignalOnlyInItsPane() {
        val bars = listOf(
            KLineBar(0L, 40.0, 60.0, 30.0, 50.0),
            KLineBar(1L, 40.0, 60.0, 30.0, 50.0),
        )
        val controller = KLineChartController()
        val engine = KLineChartEngine(StaticKLineDataSource(bars), controller)
        val price = KLinePane("price", KLinePaneKind.PRICE, 0, 1.0, 0.0, yAxes = listOf(KLineYAxis("price", 0.0, 100.0)))
        val volume = KLinePane("volume", KLinePaneKind.INDICATOR, 1, 1.0, 0.0, yAxes = listOf(KLineYAxis("volume", 0.0, 100.0)))
        controller.setPane(price)
        controller.setPane(volume)
        controller.setMarket(KLineSymbol("TEST"), KLinePeriod(1, KLinePeriodUnit.DAY))
        val bounds = KLineRect(0.0, 0.0, 100.0, 100.0)
        engine.updateBounds(bounds)
        val signal = KLineSignal("buy", 1L, 50.0, KLineSignalType.BUY, "Buy", "Reason")
        engine.setSignals(listOf(signal))

        val plan = engine.latestRenderPlan(bounds)
        assertEquals(1, plan.overlays.size)
        val signalFigure = plan.overlays.single() as com.tencent.kuiklybase.kline.render.KLineOverlayRenderFigure.Text
        assertEquals(KLinePointerDispatchOutcome.SignalClick(signal), engine.dispatchPointerEvent(KLinePointerEvent.Tap(signalFigure.anchor.x, signalFigure.anchor.y)))
        assertEquals(KLinePointerDispatchOutcome.Handled, engine.dispatchPointerEvent(KLinePointerEvent.Tap(signalFigure.anchor.x, 75.0)))
        engine.dispose()
    }

    @Test
    fun disposeDetachesController() {
        val controller = KLineChartController()
        val engine = KLineChartEngine(
            dataSource = StaticKLineDataSource(emptyList()),
            controller = controller,
        )

        engine.dispose()

        assertFailsWith<IllegalStateException> { controller.exportState() }
    }
}
