package com.kuikly.kuiklyklinechart.shared

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuiklybase.kline.KLineChartMode
import com.tencent.kuiklybase.kline.KLinePointerDispatchOutcome
import com.tencent.kuiklybase.kline.KLinePointerEvent
import com.tencent.kuiklybase.kline.KLinePriceStyle
import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.axis.KLineYAxisMode
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineDataSource
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.host.KLineChartHostView
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayConfig
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.render.KLineDrawingPrimitive
import com.tencent.kuiklybase.kline.render.KLinePrimitiveListSink
import com.tencent.kuiklybase.kline.render.KLineRenderPipeline
import com.tencent.kuiklybase.kline.signal.KLineSignal
import com.tencent.kuiklybase.kline.signal.KLineSignalType
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.controller.KLineChartState
import com.tencent.kuiklybase.kline.view.KLineChartBindingRegistry
import kotlin.math.ceil
import kotlin.math.floor

/** Kotlin/Native bridge used by the HarmonyOS external View. */
public class OhosKLineChartBridge {
    private var controller = KLineChartController()
    private var symbol = KLineSymbol("00700", "Demo Stock")
    private var period = KLinePeriod(1, KLinePeriodUnit.DAY)
    private var bars: List<KLineBar> = emptyList()
    private var currentDataSource: KLineDataSource = StaticKLineDataSource(bars)
    private var previousSnapshot: KLineStoreSnapshot? = null
    private var lastCrosshair: com.tencent.kuiklybase.kline.interaction.KLineCrosshair? = null
    private var lastPaneHeaderTops: List<Double> = emptyList()
    /** 屏幕像素密度，由 ArkTS 侧下发；用于窗格标题命中宽度与布局回传的 px/vp 换算。 */
    private var density: Double = 1.0
    private var configuredMode = KLineChartMode.FULL
    private var configuredPriceStyle = KLinePriceStyle.CANDLE
    private var configuredTheme = KLineTheme.LIGHT
    private var signals: List<KLineSignal> = emptyList()
    private var configuredConfig: String? = null
    private val configuredPaneIds = linkedSetOf("price", "volume")
    private val configuredIndicatorIds = linkedSetOf("ma-price", "ma-price-10", "vol")
    private val events = mutableListOf<String>()
    private val pipeline = KLineRenderPipeline()
    private var host = createHost()

    init {
        controller.setMarket(symbol, period)
    }

    private fun createHost(): KLineChartHostView = KLineChartHostView(
        dataSource = currentDataSource,
        controller = controller,
        onSnapshot = ::onSnapshot,
    ) {
        addDefaultIndicators()
        onError { emit("onError", JSONObject().apply { put("code", it.code.name); put("message", it.message) }) }
    }.also {
        it.attach()
        it.engine.setMode(configuredMode)
        it.engine.setPriceStyle(configuredPriceStyle)
        it.engine.setSignals(signals)
    }

    public fun setProp(key: String, value: String): Boolean {
        return try {
            when (key) {
            "symbol" -> JSONObject(value).let {
                symbol = KLineSymbol(it.optString("ticker"), it.optString("name"))
                controller.setMarket(symbol, period)
            }
            "period" -> JSONObject(value).let {
                period = KLinePeriod(it.optInt("value", 1), periodUnit(it.optString("unit")))
                controller.setMarket(symbol, period)
            }
            "mode" -> {
                configuredMode = if (value.equals("compact", true)) KLineChartMode.COMPACT else KLineChartMode.FULL
                host.engine.setMode(configuredMode)
            }
            "priceStyle" -> {
                configuredPriceStyle = if (value.equals("line", true)) KLinePriceStyle.LINE else KLinePriceStyle.CANDLE
                host.engine.setPriceStyle(configuredPriceStyle)
            }
            "bars" -> replaceBars(parseBars(value))
            "signals" -> {
                signals = parseSignals(value)
                host.engine.setSignals(signals)
            }
            "config" -> {
                configuredConfig = value
                applyConfig(JSONObject(value))
            }
            "theme" -> {
                configuredTheme = if (value.equals("dark", true)) KLineTheme.DARK else KLineTheme.LIGHT
                controller.setTheme(configuredTheme)
            }
            "bindingId" -> {
                val binding = KLineChartBindingRegistry.take(value)
                    ?: error("Unknown or expired bindingId")
                replaceDataSource(binding.dataSource, binding.controller)
            }
            "density" -> density = value.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
            else -> return false
        }
            true
        } catch (error: Exception) {
            emit("onError", JSONObject().apply {
                put("code", "INVALID_ARGUMENT")
                put("message", "Invalid $key property: ${error.message ?: error::class.simpleName}")
            })
            true
        }
    }

    public fun call(method: String, params: String): String {
        val value = if (params.isBlank()) JSONObject() else JSONObject(params)
        when (method) {
            "scrollToLatest" -> controller.scrollToLatest()
            "zoom" -> controller.zoom(value.optDouble("factor", 1.0))
            "scrollToTimestamp" -> controller.scrollToTimestamp(value.optLong("timestamp"))
            "scrollByBars" -> controller.scrollByBars(value.optDouble("count"))
            "zoomAtTimestamp" -> controller.zoomAtTimestamp(value.optDouble("factor", 1.0), value.optLong("timestamp"))
            "resetViewport" -> controller.resetViewport()
            "clearCrosshair" -> controller.clearCrosshair()
            "cancelInteraction" -> controller.cancelInteraction()
            "setPane" -> controller.setPane(parsePane(value))
            "removePane" -> controller.removePane(value.optString("paneId"))
            "movePane" -> controller.movePane(value.optString("paneId"), value.optInt("index", 0))
            "setPaneState" -> controller.setPaneState(value.optString("paneId"), com.tencent.kuiklybase.kline.pane.KLinePaneState.valueOf(value.optString("state", "normal").uppercase()))
            "addIndicator" -> controller.addIndicator(parseIndicator(value))
            "updateIndicator" -> controller.updateIndicator(parseIndicator(value))
            "removeIndicator" -> controller.removeIndicator(value.optString("id"))
            "beginOverlay" -> controller.beginOverlay(value.optString("templateName"), value.optString("paneId", "price"), magnetMode(value.optString("magnetMode")))
            "createOverlay" -> return JSONObject().apply { put("id", controller.createOverlay(parseOverlayConfig(value))) }.toString()
            "updateOverlay" -> controller.updateOverlay(value.optString("id"), parseOverlayConfig(value))
            "removeOverlay" -> controller.removeOverlay(value.optString("id"))
            "exportState" -> return stateToJson(controller.exportState())
            "restoreState" -> controller.restoreState(parseState(value))
            "deleteSelectedOverlay" -> controller.deleteSelectedOverlay()
            "loadBefore" -> host.engine.triggerLoadBefore()
            "loadAfter" -> host.engine.triggerLoadAfter()
            "retryInitialLoad" -> host.engine.retryInitialLoad()
        }
        return "{}"
    }

    public fun resize(width: Int, height: Int) {
        host.onSizeChanged(width, height)
    }

    public fun pointer(kind: String, x: Double, y: Double, scale: Double, count: Int) {
        if (kind == "tap" && x in 0.0..(76.0 * density)) {
            host.latestRenderPlan()?.panes.orEmpty().firstOrNull { pane ->
                pane.headerRect?.let { y in it.top..it.bottom } == true
            }?.let { pane ->
                emit("onPaneHeaderClick", JSONObject().apply { put("paneId", pane.id) })
                return
            }
        }
        val event = when (kind) {
            "down" -> KLinePointerEvent.Down(x, y)
            "secondaryDown" -> KLinePointerEvent.SecondaryDown(x, y)
            "move" -> KLinePointerEvent.Move(x, y, scale, count)
            "up" -> KLinePointerEvent.Up(x, y)
            "cancel" -> KLinePointerEvent.Cancel(x, y)
            "tap" -> KLinePointerEvent.Tap(x, y)
            "longPress" -> KLinePointerEvent.LongPress(x, y)
            else -> return
        }
        val outcome = host.onPointerEvent(event)
        if (outcome is KLinePointerDispatchOutcome.SignalClick) {
            emit("onSignalClick", JSONObject().apply {
                put("id", outcome.signal.id)
                put("title", outcome.signal.title)
                put("summary", outcome.signal.summary)
            })
        }
    }

    public fun renderCommands(): String {
        val plan = host.latestRenderPlan() ?: return "[]"
        // headerRect 为像素坐标，Kuikly 侧 top() 以 vp 为单位（对齐 Android 的 /density）
        val tops = listOf("price", "first", "second").map { id ->
            plan.panes.firstOrNull { it.id == id }?.headerRect?.top?.div(density) ?: 0.0
        }
        if (tops != lastPaneHeaderTops) {
            lastPaneHeaderTops = tops
            emit("onPaneLayoutChange", JSONObject().apply { put("priceTop", tops[0]); put("firstTop", tops[1]); put("secondTop", tops[2]) })
        }
        val sink = KLinePrimitiveListSink()
        pipeline.render(plan, sink)
        return JSONArray().apply {
            sink.primitives.forEach { put(primitiveJson(it)) }
        }.toString()
    }

    public fun pollEvents(): String =
        JSONArray().apply { events.forEach { put(JSONObject(it)) } }.toString().also { events.clear() }

    public fun dispose() {
        host.dispose()
        events.clear()
    }

    private fun replaceBars(next: List<KLineBar>) {
        val state = runCatching { controller.exportState() }.getOrNull()
        host.dispose()
        bars = next
        currentDataSource = StaticKLineDataSource(next)
        previousSnapshot = null
        host = createHost()
        controller.setMarket(symbol, period)
        controller.setTheme(configuredTheme)
        configuredConfig?.let { runCatching { applyConfig(JSONObject(it)) } }
        state?.let(controller::restoreState)
    }

    private fun replaceDataSource(dataSource: KLineDataSource, newController: KLineChartController) {
        val state = runCatching { controller.exportState() }.getOrNull()
        host.dispose()
        controller = newController
        currentDataSource = dataSource
        previousSnapshot = null
        host = createHost()
        controller.setMarket(symbol, period)
        controller.setTheme(configuredTheme)
        configuredConfig?.let { runCatching { applyConfig(JSONObject(it)) } }
        state?.let(controller::restoreState)
    }

    private fun onSnapshot(snapshot: KLineStoreSnapshot) {
        val previous = previousSnapshot
        previousSnapshot = snapshot
        if (previous?.viewport != snapshot.viewport) snapshot.viewport?.let {
            emit("onVisibleRangeChange", JSONObject().apply {
                put("startIndex", floor(it.startIndex).toInt())
                put("endIndex", ceil(it.endIndex).toInt())
            })
        }
        if (previous?.clickSelection != snapshot.clickSelection) snapshot.clickSelection?.let {
            emit("onBarClick", JSONObject().apply { put("timestamp", it.timestamp); put("index", it.index) })
        }
        if (previous?.loadState != snapshot.loadState) emit("onLoadStateChange", JSONObject().apply {
            put("initial", snapshot.loadState.initial.name.lowercase())
            put("before", snapshot.loadState.before.name.lowercase())
            put("after", snapshot.loadState.after.name.lowercase())
        })
        if (previous?.indicatorRevision != snapshot.indicatorRevision) emit("onIndicatorChange", JSONObject().apply {
            put("ids", snapshot.indicatorInstances.joinToString(",") { it.id })
        })
        if (previous?.selectedOverlayId != snapshot.selectedOverlayId) emit("onOverlayClick", JSONObject().apply { put("id", snapshot.selectedOverlayId) })
        if (previous?.overlayRevision != snapshot.overlayRevision) emit("onOverlayChange", JSONObject().apply { put("revision", snapshot.overlayRevision) })
        if (previous?.period != snapshot.period) snapshot.period?.let { next -> emit("onPeriodChange", JSONObject().apply { put("value", next.span); put("unit", next.unit.name.lowercase()) }) }
        if (snapshot.crosshair != lastCrosshair) {
            lastCrosshair = snapshot.crosshair
            emit("onCrosshairChange", JSONObject().apply {
                snapshot.crosshair?.let { crosshair ->
                    put("timestamp", crosshair.timestamp)
                    put("price", crosshair.value)
                    put("paneId", crosshair.paneId)
                }
            })
        }
    }

    private fun emit(name: String, payload: JSONObject) {
        val event = JSONObject().apply { put("name", name); put("payload", payload) }.toString()
        events += event
    }

    private fun primitiveJson(value: KLineDrawingPrimitive): JSONObject = JSONObject().apply {
        put("layer", value.layer.order)
        when (value) {
            is KLineDrawingPrimitive.Line -> {
                put("type", "line"); put("x1", value.start.x); put("y1", value.start.y)
                put("x2", value.end.x); put("y2", value.end.y); put("color", value.stroke.color)
                put("width", value.stroke.width); put("dash", JSONArray().apply { value.stroke.dash.forEach { put(it) } })
            }
            is KLineDrawingPrimitive.Rect -> {
                put("type", "rect"); put("left", value.bounds.left); put("top", value.bounds.top)
                put("right", value.bounds.right); put("bottom", value.bounds.bottom); put("color", value.fillColor)
                put("strokeColor", value.stroke?.color); put("strokeWidth", value.stroke?.width ?: 0.0)
            }
            is KLineDrawingPrimitive.Polyline -> {
                put("type", "polyline"); put("color", value.stroke.color); put("width", value.stroke.width)
                put("dash", JSONArray().apply { value.stroke.dash.forEach { put(it) } })
                put("points", JSONArray().apply {
                    value.points.forEach { point -> put(JSONObject().apply { put("x", point.x); put("y", point.y) }) }
                })
            }
            is KLineDrawingPrimitive.Circle -> {
                put("type", "circle"); put("x", value.center.x); put("y", value.center.y)
                put("radius", value.radius); put("color", value.color)
            }
            is KLineDrawingPrimitive.Candle -> {
                put("type", "candle"); put("x", value.x); put("open", value.openY); put("high", value.highY)
                put("low", value.lowY); put("close", value.closeY); put("bodyWidth", value.bodyWidth)
                put("color", value.color); put("wickWidth", value.wickWidth)
            }
            is KLineDrawingPrimitive.Text -> {
                put("type", "text"); put("text", value.text); put("left", value.bounds.left)
                put("top", value.bounds.top); put("right", value.bounds.right); put("bottom", value.bounds.bottom)
                put("color", value.color); put("textSize", value.textSize)
            }
        }
    }

    private fun parseBars(text: String): List<KLineBar> {
        val array = JSONArray(text)
        return List(array.length()) { index ->
            array.optJSONObject(index)!!.let {
                KLineBar(
                    it.optLong("timestamp"), it.optDouble("open"), it.optDouble("high"),
                    it.optDouble("low"), it.optDouble("close"), it.optDouble("volume"), it.optDouble("turnover"),
                )
            }
        }
    }

    private fun parseSignals(text: String): List<KLineSignal> {
        val array = JSONArray(text)
        return List(array.length()) { index ->
            array.optJSONObject(index)!!.let {
                KLineSignal(
                    id = it.optString("id"), timestamp = it.optLong("timestamp"), value = it.optDouble("value"),
                    type = KLineSignalType.valueOf(it.optString("type", "INFO").uppercase()),
                    title = it.optString("title"), summary = it.optString("summary"),
                    confidence = if (it.has("confidence")) it.optDouble("confidence") else null,
                )
            }
        }
    }

    private fun applyConfig(config: JSONObject) {
        configuredIndicatorIds.forEach(controller::removeIndicator)
        configuredPaneIds.forEach(controller::removePane)
        configuredIndicatorIds.clear()
        configuredPaneIds.clear()

        config.optJSONArray("panes")?.let { panes ->
            for (index in 0 until panes.length()) {
                val value = panes.optJSONObject(index) ?: continue
                val id = value.optString("id")
                if (id.isBlank()) continue
                // 与 setPane/restoreState 共用 parsePane：统一解析 yAxes（含 min/max/referenceValue/autoScale）与 state
                val pane = parsePane(value).let { copy ->
                    if (value.has("order")) copy else copy.copy(order = index)
                }
                controller.setPane(pane)
                configuredPaneIds += id
            }
        }
        config.optJSONArray("indicators")?.let { indicators ->
            for (index in 0 until indicators.length()) {
                val value = indicators.optJSONObject(index) ?: continue
                val id = value.optString("id")
                val template = KLineBuiltInIndicators.templates.firstOrNull {
                    it.name.equals(value.optString("template"), true)
                } ?: continue
                val params = value.optJSONArray("params")?.let { array ->
                    List(array.length()) { paramIndex -> array.optDouble(paramIndex) }
                } ?: template.defaultParams
                controller.addIndicator(template.instance(
                    id = id, paneId = value.optString("paneId", "price"), params = params,
                ))
                configuredIndicatorIds += id
            }
        }
    }

    private fun parsePane(value: JSONObject): KLinePane {
        val id = value.optString("id")
        val kind = if (value.optString("kind").equals("price", true)) KLinePaneKind.PRICE else KLinePaneKind.INDICATOR
        return KLinePane(id, kind, value.optInt("order", 0), value.optDouble("weight", if (kind == KLinePaneKind.PRICE) 3.0 else 1.0), value.optDouble("minHeight", if (kind == KLinePaneKind.PRICE) 120.0 else 60.0), KLinePaneState.valueOf(value.optString("state", "normal").uppercase()), parseYAxes(value, id))
    }

    private fun parseYAxes(value: JSONObject, paneId: String): List<KLineYAxis> =
        value.optJSONArray("yAxes")?.let { array ->
            List(array.length()) { index -> parseYAxis(array.optJSONObject(index), "$paneId-y-$index") }
        }.orEmpty().ifEmpty { listOf(KLineYAxis("$paneId-y", 0.0, 100.0, autoScale = true)) }

    private fun parseYAxis(value: JSONObject?, fallbackId: String) = KLineYAxis(
        id = value?.optString("id", fallbackId).orEmpty().ifBlank { fallbackId },
        minValue = value?.optDouble("minValue", 0.0) ?: 0.0,
        maxValue = value?.optDouble("maxValue", 100.0) ?: 100.0,
        mode = KLineYAxisMode.valueOf(value?.optString("mode", "normal").orEmpty().ifBlank { "normal" }.uppercase()),
        referenceValue = value?.takeIf { it.has("referenceValue") }?.optDouble("referenceValue"),
        autoScale = value?.optBoolean("autoScale", true) ?: true,
    )

    private fun parseIndicator(value: JSONObject): KLineIndicatorInstance {
        val templateName = value.optString("templateName").ifBlank { value.optString("template") }
        val template = KLineBuiltInIndicators.templates.firstOrNull { it.name.equals(templateName, true) }
            ?: error("Unknown indicator template: $templateName")
        val params = value.optJSONArray("params")?.let { array -> List(array.length()) { i -> array.optDouble(i) } } ?: template.defaultParams
        return template.instance(value.optString("id"), value.optString("paneId", "price"), params, value.optInt("precision", 2), value.optBoolean("visible", true))
    }

    private fun stateToJson(state: KLineChartState): String = JSONObject().apply {
        put("theme", if (state.theme == KLineTheme.DARK) "dark" else "light")
        state.viewport?.let { viewport -> put("viewport", JSONObject().apply { put("startIndex", viewport.startIndex); put("endIndex", viewport.endIndex); put("barSpace", viewport.barSpace); put("rightOffset", viewport.rightOffset); viewport.zoomAnchor?.let { put("zoomAnchor", it) } }) }
        put("panes", JSONArray().apply { state.panes.forEach { pane -> put(JSONObject().apply {
            put("id", pane.id); put("kind", pane.kind.name.lowercase()); put("order", pane.order)
            put("weight", pane.weight); put("minHeight", pane.minHeight); put("state", pane.state.name.lowercase())
            put("yAxes", JSONArray().apply { pane.yAxes.forEach { axis -> put(JSONObject().apply {
                put("id", axis.id); put("minValue", axis.minValue); put("maxValue", axis.maxValue)
                put("mode", axis.mode.name.lowercase()); put("referenceValue", axis.referenceValue)
                put("autoScale", axis.autoScale)
            }) } })
        }) } })
        put("indicators", JSONArray().apply { state.indicatorInstances.forEach { put(JSONObject().apply { put("id", it.id); put("templateName", it.templateName); put("paneId", it.paneId); put("params", JSONArray().apply { it.params.forEach(::put) }); put("precision", it.precision); put("visible", it.visible) }) } })
        put("overlays", JSONArray().apply { state.overlayInstances.forEach { overlay -> put(JSONObject().apply { put("id", overlay.id); put("templateName", overlay.templateName); put("groupId", overlay.groupId); put("paneId", overlay.paneId); put("points", JSONArray().apply { overlay.points.forEach { point -> put(JSONObject().apply { put("timestamp", point.timestamp); put("value", point.value) }) } }); put("visible", overlay.visible); put("locked", overlay.locked); put("magnetMode", overlay.magnetMode.name.lowercase()); put("zIndex", overlay.zIndex); put("styles", JSONObject().apply { overlay.styles.forEach { (key, style) -> put(key, JSONObject().apply { put("color", style.color); put("lineWidth", style.lineWidth); put("lineDash", JSONArray().apply { style.lineDash.forEach { put(it) } }); put("textSize", style.textSize) }) } }); put("extendData", JSONObject().apply { overlay.extendData.forEach { (key, item) -> put(key, item) } }) }) } })
    }.toString()

    private fun parseState(value: JSONObject): KLineChartState {
        val viewport = value.optJSONObject("viewport")?.let { KLineViewport(it.optDouble("startIndex"), it.optDouble("endIndex"), it.optDouble("barSpace"), it.optDouble("rightOffset"), if (it.has("zoomAnchor")) it.optDouble("zoomAnchor") else null) }
        val panes = value.optJSONArray("panes")?.let { array -> List(array.length()) { parsePane(array.optJSONObject(it)!!) } }.orEmpty()
        val indicators = value.optJSONArray("indicators")?.let { array -> List(array.length()) { parseIndicator(array.optJSONObject(it)!!) } }.orEmpty()
        val overlays = value.optJSONArray("overlays")?.let { array -> List(array.length()) { index -> val item = array.optJSONObject(index)!!; parseOverlayConfig(item).toInstance(item.optString("id")) } }.orEmpty()
        val theme = if (value.optString("theme").equals("dark", true)) KLineTheme.DARK else KLineTheme.LIGHT
        return KLineChartState(viewport, panes, indicators, overlays, theme)
    }

    private fun parseOverlayConfig(value: JSONObject): KLineOverlayConfig {
        val points = value.optJSONArray("points")?.let { array -> List(array.length()) { index -> array.optJSONObject(index)!!.let { KLineOverlayPoint(it.optLong("timestamp"), it.optDouble("value")) } } }.orEmpty()
        val styles = linkedMapOf<String, com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle>()
        value.optJSONObject("styles")?.let { json ->
            json.keys().forEach { key ->
                val style = json.optJSONObject(key) ?: return@forEach
                val dash = style.optJSONArray("lineDash")?.let { array -> List(array.length()) { array.optDouble(it) } }.orEmpty()
                styles[key] = com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle(
                    color = style.optString("color").ifBlank { "#2F80ED" },
                    lineWidth = style.optDouble("lineWidth", 1.0),
                    lineDash = dash,
                    textSize = style.optDouble("textSize", 12.0),
                )
            }
        }
        val extendData = linkedMapOf<String, String>()
        value.optJSONObject("extendData")?.let { json -> json.keys().forEach { key -> extendData[key] = json.optString(key) } }
        return KLineOverlayConfig(
            templateName = value.optString("templateName"), groupId = value.optString("groupId").ifBlank { null },
            paneId = value.optString("paneId", "price"), points = points,
            visible = value.optBoolean("visible", true), locked = value.optBoolean("locked", false),
            magnetMode = magnetMode(value.optString("magnetMode")), zIndex = value.optInt("zIndex", 0),
            styles = styles, extendData = extendData,
        )
    }

    private fun magnetMode(value: String): KLineOverlayMagnetMode = when (value.lowercase()) {
        "weak" -> KLineOverlayMagnetMode.WEAK
        "strong" -> KLineOverlayMagnetMode.STRONG
        else -> KLineOverlayMagnetMode.NONE
    }

    private fun periodUnit(value: String): KLinePeriodUnit = when (value.lowercase()) {
        "minute" -> KLinePeriodUnit.MINUTE
        "hour" -> KLinePeriodUnit.HOUR
        "week" -> KLinePeriodUnit.WEEK
        "month" -> KLinePeriodUnit.MONTH
        else -> KLinePeriodUnit.DAY
    }
}
