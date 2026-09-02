package com.tencent.kuiklybase.kline.host

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuiklybase.kline.KLinePointerDispatchOutcome
import com.tencent.kuiklybase.kline.KLinePointerEvent
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineCancelable
import com.tencent.kuiklybase.kline.data.KLineDataSource
import com.tencent.kuiklybase.kline.data.KLineLoadCallback
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.data.KLineLoadPage
import com.tencent.kuiklybase.kline.data.KLineLoadRequest
import com.tencent.kuiklybase.kline.data.KLineLoadResult
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLineRealtimeEvent
import com.tencent.kuiklybase.kline.data.KLineRealtimeListener
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.overlay.canonicalizeOverlayTemplateName
import com.tencent.kuiklybase.kline.view.KLineChartBindingRegistry
import com.tencent.kuiklybase.kline.view.KLineChartEvent
import com.tencent.kuiklybase.kline.view.KLineChartView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KLineHostContractTest {
    @Test
    fun hostRecognizesEveryPublishedPropMethodAndEventName() {
        val host = KLinePlatformHost(queueEvents = true)
        KLineChartView.PROP_KEYS.forEach { key ->
            assertTrue(host.setProp(key, propSeed(key)), "prop $key should be handled")
        }
        assertTrue(!host.setProp("notAChartProp", "x"))

        KLineChartView.METHOD_KEYS.forEach { method ->
            val result = host.callAsMap(method, methodSeed(method))
            assertNotNull(result, "method $method should be handled")
        }
        assertNull(host.callAsMap("notAChartMethod", "{}"))

        assertEquals(KLineChartView.VIEW_NAME, "KRKLineChart")
        assertTrue(KLineChartEvent.EVENT_KEYS.contains(KLineChartEvent.EVENT_ERROR))
        assertTrue(KLineChartEvent.EVENT_KEYS.contains(KLineChartEvent.EVENT_PANE_HEADER_CLICK))
        KLineChartView.OverlayTemplate.KEYS.forEach { key ->
            val resolved = canonicalizeOverlayTemplateName(key)
            assertTrue(
                KLineBuiltInOverlays.templates.any { it.name == resolved },
                "OverlayTemplate.$key should map to a built-in engine name (got $resolved)",
            )
        }
        assertEquals("MyOverlay", canonicalizeOverlayTemplateName("MyOverlay"))
        host.dispose()
    }

    @Test
    fun bindingIdConsumesTheRegisteredCommonBinding() {
        val id = KLineChartBindingRegistry.register(
            StaticKLineDataSource(emptyList()),
            KLineChartController(),
        )
        val bridge = OhosKLineChartBridge()
        assertTrue(bridge.setProp(KLineChartView.PROP_BINDING_ID, id))
        assertNull(KLineChartBindingRegistry.take(id))
        bridge.dispose()
    }

    @Test
    fun ohosBridgeClaimPointerMoveUsesSharedArbitrator() {
        val bridge = OhosKLineChartBridge()
        assertTrue(bridge.setProp(KLineChartView.PROP_DENSITY, "3"))
        assertEquals("PENDING", bridge.call("claimPointerMove", "20.0,4.0,1"))
        assertEquals("CHART", bridge.call("claimPointerMove", "25.0,4.0,1"))
        bridge.call("resetGestureClaim", "")
        assertEquals("PARENT", bridge.call("claimPointerMove", "4.0,25.0,1"))
        bridge.dispose()
    }

    @Test
    fun paneYAxisConfigurationSurvivesStateExport() {
        val bridge = OhosKLineChartBridge()
        val pane =
            """{"id":"price","kind":"price","order":0,"weight":3.0,"minHeight":120.0,"yAxes":[{"id":"price-main","minValue":10.0,"maxValue":20.0,"mode":"normal","autoScale":false},{"id":"price-percent","minValue":-5.0,"maxValue":5.0,"mode":"percentage","referenceValue":15.0,"autoScale":true}]}"""
        bridge.call(KLineChartView.METHOD_SET_PANE, pane)
        val state = JSONObject(bridge.call(KLineChartView.METHOD_EXPORT_STATE, "{}"))
        val pricePane = state.optJSONArray("panes")
            ?.let { panes -> (0 until panes.length()).mapNotNull(panes::optJSONObject) }
            ?.first { it.optString("id") == "price" }
        val axes = pricePane?.optJSONArray("yAxes")
        assertEquals(2, axes?.length())
        assertEquals("price-main", axes?.optJSONObject(0)?.optString("id"))
        assertEquals("percentage", axes?.optJSONObject(1)?.optString("mode"))
        assertEquals(15.0, axes?.optJSONObject(1)?.optDouble("referenceValue"))
        bridge.dispose()
    }

    private fun propSeed(key: String): String = when (key) {
        KLineChartView.PROP_SYMBOL -> """{"ticker":"00700","name":"Tencent"}"""
        KLineChartView.PROP_PERIOD -> """{"value":1,"unit":"day"}"""
        KLineChartView.PROP_THEME -> "light"
        KLineChartView.PROP_MODE -> "full"
        KLineChartView.PROP_PRICE_STYLE -> "candle"
        KLineChartView.PROP_BARS -> "[]"
        KLineChartView.PROP_SIGNALS -> "[]"
        KLineChartView.PROP_CONFIG -> """{"panes":[],"indicators":[]}"""
        KLineChartView.PROP_DENSITY -> "2"
        KLineChartView.PROP_BINDING_ID -> "expired-id"
        else -> ""
    }

    private fun methodSeed(method: String): String = when (method) {
        KLineChartView.METHOD_ZOOM, KLineChartView.METHOD_ZOOM_AT_TIMESTAMP -> """{"factor":1.0,"timestamp":0}"""
        KLineChartView.METHOD_SCROLL_TO_TIMESTAMP -> """{"timestamp":0}"""
        KLineChartView.METHOD_SCROLL_BY_BARS -> """{"count":1}"""
        KLineChartView.METHOD_BEGIN_OVERLAY ->
            """{"templateName":"${KLineChartView.OverlayTemplate.HORIZONTAL_LINE}","paneId":"price"}"""
        KLineChartView.METHOD_SET_PANE, KLineChartView.METHOD_ADD_INDICATOR, KLineChartView.METHOD_UPDATE_INDICATOR ->
            """{"id":"price","kind":"price","template":"MA","templateName":"MA","paneId":"price","params":[5]}"""
        KLineChartView.METHOD_REMOVE_PANE, KLineChartView.METHOD_MOVE_PANE, KLineChartView.METHOD_SET_PANE_STATE ->
            """{"paneId":"price","index":0,"state":"normal"}"""
        KLineChartView.METHOD_REMOVE_INDICATOR, KLineChartView.METHOD_REMOVE_OVERLAY, KLineChartView.METHOD_UPDATE_OVERLAY ->
            """{"id":"missing"}"""
        KLineChartView.METHOD_CREATE_OVERLAY ->
            """{"templateName":"${KLineChartView.OverlayTemplate.HORIZONTAL_LINE}","paneId":"price","points":[{"timestamp":0,"value":10}]}"""
        else -> "{}"
    }
}

class KLinePlatformHostCrossPlatformTest {
    @Test
    fun samePixelViewportYieldsIdenticalPaneGeometryAcrossDensities() {
        val d1 = hostWithFixture(density = 1.0)
        val d2 = hostWithFixture(density = 2.0)
        val panes1 = d1.chartHost.latestRenderPlan()!!.panes
        val panes2 = d2.chartHost.latestRenderPlan()!!.panes
        assertEquals(panes1.map { it.id to it.rect }, panes2.map { it.id to it.rect })
        d1.dispose()
        d2.dispose()
    }

    @Test
    fun paneLayoutEventsAreReportedInVpAndHeaderHitUsesDensity() {
        val events1 = mutableListOf<Map<String, Any?>>()
        val host1 = hostWithFixture(density = 1.0, onEvent = { name, payload ->
            if (name == KLineChartEvent.EVENT_PANE_LAYOUT_CHANGE) events1 += payload
        })
        host1.notifyEventListenerBound(KLineChartEvent.EVENT_PANE_LAYOUT_CHANGE)
        host1.renderCommands()
        val events2 = mutableListOf<Map<String, Any?>>()
        val host2 = hostWithFixture(density = 2.0, onEvent = { name, payload ->
            if (name == KLineChartEvent.EVENT_PANE_LAYOUT_CHANGE) events2 += payload
        })
        host2.notifyEventListenerBound(KLineChartEvent.EVENT_PANE_LAYOUT_CHANGE)
        host2.renderCommands()

        val top1 = events1.last()["priceTop"] as Double
        val top2 = events2.last()["priceTop"] as Double
        assertEquals(top1, top2 * 2.0, 1e-6)

        val header = host1.chartHost.latestRenderPlan()!!.panes.first { it.id == "price" }.headerRect!!
        val y = header.top + 1.0
        assertEquals("price", host1.paneHeaderAt(50.0, y))
        assertNull(host1.paneHeaderAt(PANE_HEADER_HIT_WIDTH_VP + 1.0, y))
        assertEquals("price", host2.paneHeaderAt(PANE_HEADER_HIT_WIDTH_VP + 1.0, y))
        host1.dispose()
        host2.dispose()
    }

    @Test
    fun pointerKindsShareKLinePointerEventWithAndroidGoldBehavior() {
        val host = hostWithFixture(density = 1.0)
        val boundsY = 80.0
        host.dispatchPointer(KLinePointerEvent.Down(120.0, boundsY))
        host.dispatchPointer(KLinePointerEvent.LongPress(120.0, boundsY))
        assertNotNull(host.engine.snapshotFlow.value.crosshair)

        host.callAsMap(KLineChartView.METHOD_CLEAR_CROSSHAIR, null)
        host.pointer("down", 120.0, boundsY, 1.0, 1)
        host.pointer("longPress", 120.0, boundsY, 1.0, 1)
        assertNotNull(host.engine.snapshotFlow.value.crosshair)

        host.dispatchPointer(KLinePointerEvent.Cancel(120.0, boundsY))
        host.dispatchPointer(KLinePointerEvent.SecondaryDown(120.0, boundsY))
        host.dispatchPointer(KLinePointerEvent.Move(120.0, boundsY, scaleFactor = 2.0, pointerCount = 2))
        assertNull(host.engine.snapshotFlow.value.crosshair)
        host.dispose()
    }

    @Test
    fun mainPathLoadRealtimeCrosshairZoomOverlaySignalAndModes() {
        val source = PushKLineDataSource(fixtureBars(count = 300))
        val controller = KLineChartController()
        val id = KLineChartBindingRegistry.register(source, controller)
        val host = KLinePlatformHost(queueEvents = true)
        assertTrue(host.setProp(KLineChartView.PROP_BINDING_ID, id))
        host.setProp(KLineChartView.PROP_DENSITY, "1")
        host.setProp(KLineChartView.PROP_CONFIG, THREE_PANES)
        host.resize(PIXEL_WIDTH, PIXEL_HEIGHT)

        var state = JSONObject(host.call(KLineChartView.METHOD_EXPORT_STATE, "{}"))
        assertTrue(state.optJSONArray("panes")!!.length() >= 2)
        assertEquals(200, host.engine.snapshotFlow.value.bars.size)
        assertTrue(host.engine.snapshotFlow.value.hasMoreBefore)

        host.callAsMap(KLineChartView.METHOD_LOAD_BEFORE, null)
        assertEquals(300, host.engine.snapshotFlow.value.bars.size)

        val last = host.engine.snapshotFlow.value.bars.last()
        source.push(last.copy(timestamp = last.timestamp + 86_400_000L, close = last.close + 1))
        assertEquals(301, host.engine.snapshotFlow.value.bars.size)

        val beforeSpace = host.engine.snapshotFlow.value.viewport!!.barSpace
        host.callAsMap(KLineChartView.METHOD_ZOOM, """{"factor":2.0}""")
        assertTrue(host.engine.snapshotFlow.value.viewport!!.barSpace > beforeSpace)

        host.callAsMap(KLineChartView.METHOD_SCROLL_BY_BARS, """{"count":-5}""")
        host.dispatchPointer(KLinePointerEvent.Down(120.0, 80.0))
        host.dispatchPointer(KLinePointerEvent.LongPress(120.0, 80.0))
        assertNotNull(host.engine.snapshotFlow.value.crosshair)
        host.callAsMap(KLineChartView.METHOD_CLEAR_CROSSHAIR, null)

        val overlay = host.callAsMap(
            KLineChartView.METHOD_CREATE_OVERLAY,
            """{"templateName":"${KLineChartView.OverlayTemplate.HORIZONTAL_LINE}","paneId":"price","points":[{"timestamp":${last.timestamp},"value":${last.close}}]}""",
        )
        assertTrue(overlay!!["id"].toString().isNotBlank())
        assertTrue(host.chartHost.latestRenderPlan()!!.overlays.isNotEmpty())

        host.callAsMap(KLineChartView.METHOD_SCROLL_TO_LATEST, null)
        val signalBar = host.engine.snapshotFlow.value.bars.last()
        host.setProp(
            KLineChartView.PROP_SIGNALS,
            """[{"id":"buy-1","timestamp":${signalBar.timestamp},"value":${signalBar.close},"type":"BUY","title":"Buy","summary":"Reason","confidence":0.8}]""",
        )
        val text = host.chartHost.latestRenderPlan()!!.overlays
            .filterIsInstance<com.tencent.kuiklybase.kline.render.KLineOverlayRenderFigure.Text>()
            .first { it.text == "Buy" }
        val signalOutcome = host.dispatchPointer(KLinePointerEvent.Tap(text.anchor.x, text.anchor.y))
        assertTrue(
            signalOutcome is KLinePointerDispatchOutcome.SignalClick,
            "signal tap expected SignalClick, got $signalOutcome at ${text.anchor.x},${text.anchor.y}",
        )

        host.setProp(KLineChartView.PROP_MODE, "compact")
        assertTrue(!host.chartHost.latestRenderPlan()!!.features.interaction)
        assertEquals(KLinePointerDispatchOutcome.Ignored, host.dispatchPointer(KLinePointerEvent.Tap(10.0, 10.0)))
        host.setProp(KLineChartView.PROP_MODE, "full")
        host.dispose()
    }

    @Test
    fun detachReleasesEngineAndAttachRestoresExportedState() {
        val host = hostWithFixture(density = 1.0)
        host.callAsMap(KLineChartView.METHOD_ZOOM, """{"factor":1.5}""")
        val before = JSONObject(host.call(KLineChartView.METHOD_EXPORT_STATE, "{}"))
        val savedBarSpace = before.optJSONObject("viewport")!!.optDouble("barSpace")

        host.onDetached()
        assertFailsWith<IllegalStateException> { host.chartController.exportState() }

        host.attachIfNeeded(PIXEL_WIDTH, PIXEL_HEIGHT)
        val after = JSONObject(host.call(KLineChartView.METHOD_EXPORT_STATE, "{}"))
        assertEquals(savedBarSpace, after.optJSONObject("viewport")!!.optDouble("barSpace"), 1e-9)
        host.dispose()
        assertFailsWith<IllegalStateException> { host.chartController.exportState() }
    }

    private fun hostWithFixture(
        density: Double,
        onEvent: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    ): KLinePlatformHost {
        val host = KLinePlatformHost(onEvent = onEvent, queueEvents = true)
        host.setProp(KLineChartView.PROP_DENSITY, density.toString())
        host.setProp(KLineChartView.PROP_BARS, encodeBars(fixtureBars(80)))
        host.setProp(KLineChartView.PROP_CONFIG, THREE_PANES)
        host.resize(PIXEL_WIDTH, PIXEL_HEIGHT)
        host.renderCommands()
        return host
    }

    companion object {
        private const val PIXEL_WIDTH = 800
        private const val PIXEL_HEIGHT = 400
        private val THREE_PANES =
            """{"panes":[{"id":"price","kind":"price","order":0,"weight":3.0,"minHeight":160},{"id":"first","kind":"indicator","order":1,"weight":1.0,"minHeight":64},{"id":"second","kind":"indicator","order":2,"weight":1.0,"minHeight":64}],"indicators":[{"id":"ma5","template":"MA","paneId":"price","params":[5]},{"id":"vol","template":"VOL","paneId":"first"}]}"""
    }
}

private class PushKLineDataSource(initial: List<KLineBar>) : KLineDataSource {
    private val bars = initial.toMutableList()
    private var listener: KLineRealtimeListener? = null

    override fun load(request: KLineLoadRequest, callback: KLineLoadCallback): KLineCancelable {
        val sorted = bars.sortedBy { it.timestamp }
        val page = when (request.direction) {
            KLineLoadDirection.INITIAL -> {
                val slice = sorted.takeLast(request.limit)
                KLineLoadPage(slice, hasMoreBefore = slice.size < sorted.size, hasMoreAfter = false)
            }
            KLineLoadDirection.BEFORE -> {
                val anchor = request.anchorTimestamp!!
                val earlier = sorted.filter { it.timestamp < anchor }.takeLast(request.limit)
                KLineLoadPage(earlier, hasMoreBefore = sorted.count { it.timestamp < (earlier.firstOrNull()?.timestamp ?: anchor) } > 0, hasMoreAfter = true)
            }
            KLineLoadDirection.AFTER -> {
                val later = sorted.filter { it.timestamp > request.anchorTimestamp!! }.take(request.limit)
                KLineLoadPage(later, hasMoreBefore = true, hasMoreAfter = later.size >= request.limit)
            }
        }
        callback.onResult(KLineLoadResult.Success(page))
        return KLineCancelable {}
    }

    override fun subscribe(symbol: KLineSymbol, period: KLinePeriod, listener: KLineRealtimeListener): KLineCancelable {
        this.listener = listener
        return KLineCancelable { this.listener = null }
    }

    fun push(bar: KLineBar) {
        bars += bar
        listener?.onEvent(KLineRealtimeEvent.Bar(bar))
    }
}

private fun fixtureBars(count: Int): List<KLineBar> = List(count) { index ->
    val close = 100.0 + index
    KLineBar(
        timestamp = 1_700_000_000_000L + index * 86_400_000L,
        open = close - 1,
        high = close + 1,
        low = close - 2,
        close = close,
        volume = 1000.0 + index,
        turnover = 10_000.0 + index,
    )
}

private fun encodeBars(bars: List<KLineBar>): String = JSONArray().apply {
    bars.forEach { bar ->
        put(JSONObject().apply {
            put("timestamp", bar.timestamp)
            put("open", bar.open)
            put("high", bar.high)
            put("low", bar.low)
            put("close", bar.close)
            put("volume", bar.volume)
            put("turnover", bar.turnover)
        })
    }
}.toString()
