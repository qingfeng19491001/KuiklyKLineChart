package com.tencent.kuiklybase.kline.controller

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineDataSession
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class KLineChartControllerTest {
    @Test
    fun queuedAndAttachedCommandsDriveViewportAndPanesThroughOneStore() {
        val bars = (0L until 100L).map(::bar)
        val store = KLineStore()
        val session = KLineDataSession(
            dataSource = StaticKLineDataSource(bars),
            store = store,
        )
        val runtime = KLineChartRuntime(store = store, dataSession = session)
        runtime.updatePlotRect(KLineRect(0.0, 0.0, 200.0, 400.0))
        val controller = KLineChartController()
        val pricePane = pane("price", KLinePaneKind.PRICE, 0)
        val volumePane = pane("volume", KLinePaneKind.INDICATOR, 1)

        controller.setMarket(
            symbol = KLineSymbol(ticker = "000001.SZ"),
            period = KLinePeriod(span = 1, unit = KLinePeriodUnit.DAY),
        )
        controller.setPane(pricePane)
        controller.zoom(2.0)
        controller.attach(runtime)

        assertEquals(20.0, store.snapshot.viewport!!.barSpace, 1e-9)
        assertEquals(listOf("price"), store.snapshot.panes.map(KLinePane::id))

        controller.scrollToTimestamp(50_000L)
        assertEquals(45.0, store.snapshot.viewport!!.startIndex, 1e-9)
        assertEquals(55.0, store.snapshot.viewport!!.endIndex, 1e-9)
        controller.scrollByBars(5.0)
        assertEquals(50.0, store.snapshot.viewport!!.startIndex, 1e-9)

        controller.setPane(volumePane)
        controller.movePane("volume", index = 0)
        controller.setPaneState("volume", KLinePaneState.MAXIMIZED)
        assertEquals(listOf("volume", "price"), store.snapshot.panes.map(KLinePane::id))
        assertEquals(KLinePaneState.MAXIMIZED, store.snapshot.panes.first().state)

        controller.detach(runtime)
        controller.scrollToLatest()
        assertEquals(60.0, store.snapshot.viewport!!.endIndex, 1e-9)
        controller.attach(runtime)
        assertEquals(99.0, store.snapshot.viewport!!.endIndex, 1e-9)
        controller.resetViewport()
        assertEquals(10.0, store.snapshot.viewport!!.barSpace, 1e-9)
    }

    @Test
    fun realtimeFollowAndHistoryPrependMaintainViewportInvariants() {
        val initialBars = (0L until 100L).map(::bar)
        val store = KLineStore()
        val session = KLineDataSession(StaticKLineDataSource(initialBars), store)
        val runtime = KLineChartRuntime(store, session)
        val rect = KLineRect(0.0, 0.0, 200.0, 400.0)
        runtime.updatePlotRect(rect)
        val controller = KLineChartController()
        controller.attach(runtime)
        controller.setMarket(
            KLineSymbol(ticker = "000001.SZ"),
            KLinePeriod(span = 1, unit = KLinePeriodUnit.DAY),
        )

        controller.scrollByBars(-10.0)
        store.applyRealtime(bar(100))
        assertEquals(89.0, store.snapshot.viewport!!.endIndex, 1e-9)
        assertEquals(-11.0, store.snapshot.viewport!!.rightOffset, 1e-9)

        controller.scrollToLatest()
        store.applyRealtime(bar(101))
        assertEquals(101.0, store.snapshot.viewport!!.endIndex, 1e-9)

        val beforePrependPixel = KLineXCoordinateSystem(
            rect,
            store.snapshot.viewport!!,
            store.snapshot.bars,
        ).timestampToPixel(50_000L)
        store.prepend(listOf(bar(-2), bar(-1)), hasMoreBefore = false)
        val afterPrependPixel = KLineXCoordinateSystem(
            rect,
            store.snapshot.viewport!!,
            store.snapshot.bars,
        ).timestampToPixel(50_000L)
        assertEquals(beforePrependPixel!!, afterPrependPixel!!, 1e-9)
    }

    @Test
    fun queuedIndicatorCommandsAddUpdateAndRemoveInstancesThroughTheStore() {
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource((1L..4L).map(::bar)), store))
        val controller = KLineChartController()
        val indicator = KLineIndicatorInstance(
            id = "price-ma",
            templateName = "MA",
            paneId = "price",
            params = listOf(3.0),
            precision = 2,
        )

        controller.addIndicator(indicator)
        controller.setMarket(KLineSymbol("000001.SZ"), KLinePeriod(1, KLinePeriodUnit.DAY))
        controller.attach(runtime)
        assertEquals(listOf(null, null, 10.0, 10.0), store.snapshot.indicatorResults.getValue("price-ma").figures.single().values)

        controller.updateIndicator(indicator.copy(params = listOf(2.0), visible = false))
        assertEquals(emptyMap(), store.snapshot.indicatorResults)
        controller.removeIndicator("price-ma")
        assertEquals(emptyList(), store.snapshot.indicatorInstances)
    }

    private fun pane(
        id: String,
        kind: KLinePaneKind,
        order: Int,
    ): KLinePane = KLinePane(
        id = id,
        kind = kind,
        order = order,
        weight = if (kind == KLinePaneKind.PRICE) 3.0 else 1.0,
        minHeight = if (kind == KLinePaneKind.PRICE) 100.0 else 60.0,
        yAxes = listOf(KLineYAxis(id = "$id-axis", minValue = 1.0, maxValue = 100.0)),
    )

    private fun bar(index: Long): KLineBar = KLineBar(
        timestamp = index * 1_000L,
        open = 10.0,
        high = 10.0,
        low = 10.0,
        close = 10.0,
    )
}
