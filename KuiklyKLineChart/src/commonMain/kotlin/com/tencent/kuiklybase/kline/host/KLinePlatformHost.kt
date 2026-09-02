package com.tencent.kuiklybase.kline.host

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuiklybase.kline.KLineChartMode
import com.tencent.kuiklybase.kline.KLinePointerDispatchOutcome
import com.tencent.kuiklybase.kline.KLinePointerEvent
import com.tencent.kuiklybase.kline.KLinePriceStyle
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.axis.KLineYAxisMode
import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.controller.KLineChartState
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineDataSource
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.host.canvas.KLineCanvasAdapter
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.interaction.KLineCrosshair
import com.tencent.kuiklybase.kline.overlay.KLineOverlayConfig
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.overlay.canonicalizeOverlayTemplateName
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import com.tencent.kuiklybase.kline.render.KLineDrawingPrimitive
import com.tencent.kuiklybase.kline.render.KLinePrimitiveListSink
import com.tencent.kuiklybase.kline.render.KLineRenderPipeline
import com.tencent.kuiklybase.kline.render.KLineRenderPlan
import com.tencent.kuiklybase.kline.signal.KLineSignal
import com.tencent.kuiklybase.kline.signal.KLineSignalType
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.view.KLineChartBindingRegistry
import com.tencent.kuiklybase.kline.view.KLineChartEvent
import com.tencent.kuiklybase.kline.view.KLineChartView
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import kotlin.math.ceil
import kotlin.math.floor

/** Pane-header hit strip width in vp; Host converts to pixels via [KLinePlatformHost.density]. */
internal const val PANE_HEADER_HIT_WIDTH_VP = 76.0

/**
 * Unified expand-View host runtime. Viewport and pane layout are computed in pixel space (Android gold).
 * Shells inject [KLineChartView.PROP_DENSITY] once. OHOS queues events and polls via [pollEvents].
 */
public class KLinePlatformHost(
    private val onInvalidate: () -> Unit = {},
    private val onEvent: (name: String, payload: Map<String, Any?>) -> Unit = { _, _ -> },
    private val queueEvents: Boolean = false,
) {
    public var density: Double = 1.0

    private val gestureArbitrator = KLineGestureArbitrator()
    private var controller = KLineChartController()
    private var symbol = KLineSymbol("00700", "Demo Stock")
    private var period = KLinePeriod(1, KLinePeriodUnit.DAY)
    private var previousSnapshot: KLineStoreSnapshot? = null
    private var lastCrosshair: KLineCrosshair? = null
    private var lastPaneHeaderTops: List<Double> = emptyList()
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private val configuredPaneIds = linkedSetOf("price", "volume")
    private val configuredIndicatorIds = linkedSetOf("ma-price", "ma-price-10", "vol")
    private var configuredMode = KLineChartMode.FULL
    private var configuredPriceStyle = KLinePriceStyle.CANDLE
    private var configuredTheme = KLineTheme.LIGHT
    private var configuredSignals: List<KLineSignal> = emptyList()
    private var configuredConfig: String? = null
    private var pendingBridgeError: String? = null
    private var currentDataSource: KLineDataSource = StaticKLineDataSource(emptyList())
    private var detachedState: KLineChartState? = null
    private var hasErrorListener = false
    private val queuedEvents = mutableListOf<String>()
    private val pipeline = KLineRenderPipeline()
    private var innerView = createInnerView(currentDataSource)

    public val chartHost: KLineChartHostView get() = innerView
    internal val engine get() = innerView.engine
    public val chartController: KLineChartController get() = controller

    init {
        controller.setMarket(symbol, period)
    }

    private fun createInnerView(dataSource: KLineDataSource): KLineChartHostView = KLineChartHostView(
        dataSource = dataSource,
        controller = controller,
        onInvalidate = onInvalidate,
    ) {
        addDefaultIndicators()
        onError { error ->
            emit(KLineChartEvent.EVENT_ERROR, mapOf("code" to error.code.name, "message" to error.message))
        }
    }.also { view ->
        // Must bind before attach(); snapshotFlow starts emitting in attach().
        view.setSnapshotListener { snapshot ->
            dispatchSnapshotEvents(previousSnapshot, snapshot)
            previousSnapshot = snapshot
            val crosshair = snapshot.crosshair
            if (crosshair != lastCrosshair) {
                lastCrosshair = crosshair
                emit(
                    KLineChartEvent.EVENT_CROSSHAIR_CHANGE,
                    if (crosshair == null) emptyMap() else mapOf(
                        "timestamp" to crosshair.timestamp,
                        "price" to crosshair.value,
                        "paneId" to crosshair.paneId,
                    ),
                )
            }
        }
        view.attach()
        view.engine.setMode(configuredMode)
        view.engine.setPriceStyle(configuredPriceStyle)
        view.engine.setSignals(configuredSignals)
    }

    fun notifyEventListenerBound(event: String) {
        if (event == KLineChartEvent.EVENT_ERROR) {
            hasErrorListener = true
            pendingBridgeError?.let(::emitBridgeError)
            pendingBridgeError = null
        }
        if (event == KLineChartEvent.EVENT_PANE_LAYOUT_CHANGE) {
            clearPaneLayoutCache()
            onInvalidate()
        }
    }

    fun setProp(propKey: String, propValue: String): Boolean = try {
        when (propKey) {
            KLineChartView.PROP_BINDING_ID -> {
                KLineChartBindingRegistry.take(propValue)?.let { binding ->
                    replaceDataSource(binding.dataSource, binding.controller)
                } ?: emitBridgeError("Unknown or expired bindingId")
                true
            }
            KLineChartView.PROP_SYMBOL -> {
                val value = JSONObject(propValue)
                symbol = KLineSymbol(value.optString("ticker"), value.optString("name"))
                controller.setMarket(symbol, period)
                true
            }
            KLineChartView.PROP_PERIOD -> {
                val value = JSONObject(propValue)
                period = KLinePeriod(value.optInt("value", 1), periodUnit(value.optString("unit")))
                controller.setMarket(symbol, period)
                true
            }
            KLineChartView.PROP_THEME -> {
                configuredTheme = if (propValue == "dark") KLineTheme.DARK else KLineTheme.LIGHT
                controller.setTheme(configuredTheme)
                true
            }
            KLineChartView.PROP_MODE -> {
                configuredMode = if (propValue.equals("compact", true)) KLineChartMode.COMPACT else KLineChartMode.FULL
                innerView.engine.setMode(configuredMode)
                onInvalidate()
                true
            }
            KLineChartView.PROP_PRICE_STYLE -> {
                configuredPriceStyle = if (propValue.equals("line", true)) KLinePriceStyle.LINE else KLinePriceStyle.CANDLE
                innerView.engine.setPriceStyle(configuredPriceStyle)
                onInvalidate()
                true
            }
            KLineChartView.PROP_BARS -> {
                runBridgeUpdate(KLineChartView.PROP_BARS) { replaceBars(parseBars(propValue)) }
                true
            }
            KLineChartView.PROP_SIGNALS -> {
                runBridgeUpdate(KLineChartView.PROP_SIGNALS) {
                    configuredSignals = parseSignals(propValue)
                    innerView.engine.setSignals(configuredSignals)
                    onInvalidate()
                }
                true
            }
            KLineChartView.PROP_CONFIG -> {
                runBridgeUpdate(KLineChartView.PROP_CONFIG) {
                    applyConfig(JSONObject(propValue))
                    configuredConfig = propValue
                }
                true
            }
            KLineChartView.PROP_DENSITY -> {
                density = propValue.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
                true
            }
            else -> false
        }
    } catch (error: Exception) {
        val message = "Invalid $propKey property: ${error.message ?: error::class.simpleName}"
        stashOrEmitBridgeError(message)
        true
    }

    fun call(method: String, params: String?): String {
        val map = callAsMap(method, params) ?: return "{}"
        return mapToJson(map).toString()
    }

    /**
     * Payload map for methods with results, empty map for successful void methods,
     * or null when [method] is not a chart bridge call (platform shells may fall through).
     */
    fun callAsMap(method: String, params: String?): Map<String, Any?>? {
        return try {
            var handled = true
            val value = if (params.isNullOrBlank()) JSONObject() else JSONObject(params)
            val result: Map<String, Any?> = when (method) {
                KLineChartView.METHOD_SCROLL_TO_LATEST -> { controller.scrollToLatest(); emptyMap() }
                KLineChartView.METHOD_ZOOM -> { controller.zoom(value.optDouble("factor", 1.0)); emptyMap() }
                KLineChartView.METHOD_SCROLL_TO_TIMESTAMP -> { controller.scrollToTimestamp(value.optLong("timestamp")); emptyMap() }
                KLineChartView.METHOD_SCROLL_BY_BARS -> { controller.scrollByBars(value.optDouble("count")); emptyMap() }
                KLineChartView.METHOD_ZOOM_AT_TIMESTAMP -> {
                    controller.zoomAtTimestamp(value.optDouble("factor", 1.0), value.optLong("timestamp"))
                    emptyMap()
                }
                KLineChartView.METHOD_LOAD_BEFORE -> { innerView.triggerLoadBefore(); emptyMap() }
                KLineChartView.METHOD_LOAD_AFTER -> { innerView.triggerLoadAfter(); emptyMap() }
                KLineChartView.METHOD_RETRY_INITIAL_LOAD -> { innerView.retryInitialLoad(); emptyMap() }
                KLineChartView.METHOD_RESET_VIEWPORT -> { controller.resetViewport(); emptyMap() }
                KLineChartView.METHOD_BEGIN_OVERLAY -> {
                    controller.beginOverlay(
                        canonicalizeOverlayTemplateName(value.optString("templateName")),
                        value.optString("paneId", "price"),
                        magnetMode(value.optString("magnetMode")),
                    )
                    emptyMap()
                }
                KLineChartView.METHOD_CANCEL_INTERACTION -> { controller.cancelInteraction(); emptyMap() }
                KLineChartView.METHOD_CLEAR_CROSSHAIR -> { controller.clearCrosshair(); emptyMap() }
                KLineChartView.METHOD_DELETE_SELECTED_OVERLAY -> { controller.deleteSelectedOverlay(); emptyMap() }
                KLineChartView.METHOD_SET_PANE -> { controller.setPane(parsePane(value)); emptyMap() }
                KLineChartView.METHOD_REMOVE_PANE -> { controller.removePane(value.optString("paneId")); emptyMap() }
                KLineChartView.METHOD_MOVE_PANE -> { controller.movePane(value.optString("paneId"), value.optInt("index", 0)); emptyMap() }
                KLineChartView.METHOD_SET_PANE_STATE -> {
                    controller.setPaneState(
                        value.optString("paneId"),
                        KLinePaneState.valueOf(value.optString("state", "normal").uppercase()),
                    )
                    emptyMap()
                }
                KLineChartView.METHOD_ADD_INDICATOR -> { controller.addIndicator(parseIndicator(value)); emptyMap() }
                KLineChartView.METHOD_UPDATE_INDICATOR -> { controller.updateIndicator(parseIndicator(value)); emptyMap() }
                KLineChartView.METHOD_REMOVE_INDICATOR -> { controller.removeIndicator(value.optString("id")); emptyMap() }
                KLineChartView.METHOD_CREATE_OVERLAY -> mapOf("id" to controller.createOverlay(parseOverlayConfig(value)))
                KLineChartView.METHOD_UPDATE_OVERLAY -> {
                    controller.updateOverlay(value.optString("id"), parseOverlayConfig(value))
                    emptyMap()
                }
                KLineChartView.METHOD_REMOVE_OVERLAY -> { controller.removeOverlay(value.optString("id")); emptyMap() }
                KLineChartView.METHOD_EXPORT_STATE -> stateToMap(controller.exportState())
                KLineChartView.METHOD_RESTORE_STATE -> { controller.restoreState(parseState(value)); emptyMap() }
                else -> {
                    handled = false
                    emptyMap()
                }
            }
            if (!handled) return null
            onInvalidate()
            result
        } catch (error: Exception) {
            val message = "Invalid $method arguments: ${error.message ?: error::class.simpleName}"
            emitBridgeError(message)
            mapOf("error" to message)
        }
    }

    fun resize(width: Int, height: Int) {
        if (width == currentWidth && height == currentHeight) return
        currentWidth = width
        currentHeight = height
        innerView.onSizeChanged(width, height)
        onInvalidate()
    }

    fun draw(canvas: KLineCanvasAdapter) {
        innerView.draw(canvas)
        emitPaneLayoutIfNeeded()
    }

    fun dispatchPointer(event: KLinePointerEvent): KLinePointerDispatchOutcome {
        val outcome = innerView.onPointerEvent(event)
        if (outcome is KLinePointerDispatchOutcome.SignalClick) {
            val signal = outcome.signal
            emit(KLineChartEvent.EVENT_SIGNAL_CLICK, mapOf("id" to signal.id, "title" to signal.title, "summary" to signal.summary))
        }
        onInvalidate()
        return outcome
    }

    fun claimPointerMove(deltaX: Double, deltaY: Double, pointerCount: Int): KLineGestureClaim =
        gestureArbitrator.move(deltaX, deltaY, pointerCount, KLINE_TOUCH_SLOP_VP * density.coerceAtLeast(0.0001))

    fun resetGestureClaim() {
        gestureArbitrator.reset()
    }

    fun pointer(kind: String, x: Double, y: Double, scale: Double, count: Int) {
        if (kind == "tap") {
            paneHeaderAt(x, y)?.let { paneId ->
                emit(KLineChartEvent.EVENT_PANE_HEADER_CLICK, mapOf("paneId" to paneId))
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
        dispatchPointer(event)
    }

    fun paneHeaderAt(x: Double, y: Double): String? {
        if (x !in 0.0..(PANE_HEADER_HIT_WIDTH_VP * density)) return null
        return innerView.latestRenderPlan()?.panes.orEmpty().firstOrNull { pane ->
            val header = pane.headerRect ?: return@firstOrNull false
            y in header.top..header.bottom
        }?.id
    }

    fun renderCommands(): String {
        val plan = innerView.latestRenderPlan() ?: return "[]"
        emitPaneLayoutIfNeeded(plan)
        val sink = KLinePrimitiveListSink()
        pipeline.render(plan, sink)
        return JSONArray().apply {
            sink.primitives.forEach { put(primitiveJson(it)) }
        }.toString()
    }

    fun pollEvents(): String =
        JSONArray().apply { queuedEvents.forEach { put(JSONObject(it)) } }.toString().also { queuedEvents.clear() }

    fun detach(): KLineChartState? {
        resetGestureClaim()
        detachedState = runCatching { controller.exportState() }.getOrNull()
        innerView.dispose()
        return detachedState
    }

    fun attachIfNeeded(width: Int, height: Int) {
        if (detachedState == null) return
        innerView = createInnerView(currentDataSource)
        resize(width, height)
        controller.setMarket(symbol, period)
        controller.setTheme(configuredTheme)
        configuredConfig?.let { runCatching { applyConfig(JSONObject(it)) } }
        detachedState?.let(controller::restoreState)
        detachedState = null
        onInvalidate()
    }

    fun onDetached(): KLineChartState? = detach()

    fun onAttached(width: Int, height: Int, restore: KLineChartState?) {
        detachedState = restore
        attachIfNeeded(width, height)
    }

    fun dispose() {
        resetGestureClaim()
        innerView.dispose()
        queuedEvents.clear()
    }

    private fun clearPaneLayoutCache() {
        lastPaneHeaderTops = emptyList()
    }

    private fun emitPaneLayoutIfNeeded(plan: KLineRenderPlan? = innerView.latestRenderPlan()) {
        val panes = plan?.panes.orEmpty()
        val tops = listOf("price", "first", "second").map { paneId ->
            panes.firstOrNull { it.id == paneId }?.headerRect?.top?.div(density) ?: 0.0
        }
        if (tops != lastPaneHeaderTops) {
            lastPaneHeaderTops = tops
            emit(
                KLineChartEvent.EVENT_PANE_LAYOUT_CHANGE,
                mapOf(
                    "priceTop" to (tops.getOrNull(0) ?: 0.0),
                    "firstTop" to (tops.getOrNull(1) ?: 0.0),
                    "secondTop" to (tops.getOrNull(2) ?: 0.0),
                ),
            )
        }
    }

    private fun replaceBars(bars: List<KLineBar>) {
        replaceDataSource(StaticKLineDataSource(bars), controller, preserveState = true)
    }

    private fun replaceDataSource(
        dataSource: KLineDataSource,
        newController: KLineChartController,
        preserveState: Boolean = false,
    ) {
        val state = if (preserveState) runCatching { controller.exportState() }.getOrNull() else null
        innerView.dispose()
        controller = newController
        currentDataSource = dataSource
        previousSnapshot = null
        resetConfiguredComponents()
        innerView = createInnerView(dataSource)
        if (currentWidth > 0 && currentHeight > 0) innerView.onSizeChanged(currentWidth, currentHeight)
        controller.setMarket(symbol, period)
        controller.setTheme(configuredTheme)
        configuredConfig?.let { runCatching { applyConfig(JSONObject(it)) } }
        state?.let(controller::restoreState)
        onInvalidate()
    }

    private fun dispatchSnapshotEvents(previous: KLineStoreSnapshot?, current: KLineStoreSnapshot) {
        if (previous?.viewport != current.viewport) current.viewport?.let { viewport ->
            emit(
                KLineChartEvent.EVENT_VISIBLE_RANGE_CHANGE,
                mapOf(
                    "startIndex" to floor(viewport.startIndex).toInt(),
                    "endIndex" to ceil(viewport.endIndex).toInt(),
                ),
            )
        }
        if (previous?.clickSelection != current.clickSelection) current.clickSelection?.let { selected ->
            emit(KLineChartEvent.EVENT_BAR_CLICK, mapOf("timestamp" to selected.timestamp, "index" to selected.index))
        }
        if (previous?.loadState != current.loadState) {
            emit(
                KLineChartEvent.EVENT_LOAD_STATE_CHANGE,
                mapOf(
                    "initial" to current.loadState.initial.name.lowercase(),
                    "before" to current.loadState.before.name.lowercase(),
                    "after" to current.loadState.after.name.lowercase(),
                ),
            )
        }
        if (previous?.selectedOverlayId != current.selectedOverlayId) {
            emit(KLineChartEvent.EVENT_OVERLAY_CLICK, mapOf("id" to current.selectedOverlayId))
        }
        if (previous?.overlayRevision != current.overlayRevision) {
            emit(KLineChartEvent.EVENT_OVERLAY_CHANGE, mapOf("revision" to current.overlayRevision))
        }
        if (previous?.period != current.period) current.period?.let { value ->
            emit(KLineChartEvent.EVENT_PERIOD_CHANGE, mapOf("value" to value.span, "unit" to value.unit.name.lowercase()))
        }
        if (previous?.indicatorRevision != current.indicatorRevision) {
            emit(KLineChartEvent.EVENT_INDICATOR_CHANGE, mapOf("ids" to current.indicatorInstances.joinToString(",") { it.id }))
        }
    }

    private inline fun runBridgeUpdate(property: String, update: () -> Unit) {
        try {
            update()
        } catch (error: Exception) {
            stashOrEmitBridgeError("Invalid $property JSON: ${error.message ?: error::class.simpleName}")
        }
    }

    /** OHOS polls events (no listener bind); Android/iOS may bind onError late. */
    private fun stashOrEmitBridgeError(message: String) {
        if (!hasErrorListener && !queueEvents) pendingBridgeError = message else emitBridgeError(message)
    }

    private fun emitBridgeError(message: String) {
        emit(KLineChartEvent.EVENT_ERROR, mapOf("code" to "INVALID_ARGUMENT", "message" to message))
    }

    private fun emit(name: String, payload: Map<String, Any?>) {
        onEvent(name, payload)
        if (queueEvents) {
            queuedEvents += JSONObject().apply {
                put("name", name)
                put("payload", mapToJson(payload))
            }.toString()
        }
    }

    private fun resetConfiguredComponents() {
        configuredPaneIds.clear()
        configuredPaneIds += listOf("price", "volume")
        configuredIndicatorIds.clear()
        configuredIndicatorIds += listOf("ma-price", "ma-price-10", "vol")
    }

    private fun parseBars(json: String): List<KLineBar> {
        val values = JSONArray(json)
        return List(values.length()) { index ->
            val item = values.optJSONObject(index)!!
            KLineBar(
                timestamp = item.optLong("timestamp"),
                open = item.optDouble("open"),
                high = item.optDouble("high"),
                low = item.optDouble("low"),
                close = item.optDouble("close"),
                volume = item.optDouble("volume"),
                turnover = item.optDouble("turnover"),
            )
        }
    }

    private fun parseSignals(json: String): List<KLineSignal> {
        val values = JSONArray(json)
        return List(values.length()) { index ->
            val item = values.optJSONObject(index)!!
            KLineSignal(
                id = item.optString("id"),
                timestamp = item.optLong("timestamp"),
                value = item.optDouble("value"),
                type = KLineSignalType.valueOf(item.optString("type", "INFO").uppercase()),
                title = item.optString("title"),
                summary = item.optString("summary"),
                confidence = if (item.has("confidence")) item.optDouble("confidence") else null,
            )
        }
    }

    private fun parsePane(value: JSONObject): KLinePane {
        val id = value.optString("id")
        val kind = if (value.optString("kind").equals("price", true)) KLinePaneKind.PRICE else KLinePaneKind.INDICATOR
        val axes = value.optJSONArray("yAxes")?.let { array ->
            List(array.length()) { index -> parseYAxis(array.optJSONObject(index), "$id-y-$index") }
        }.orEmpty().ifEmpty { listOf(KLineYAxis("$id-y", 0.0, 100.0, autoScale = true)) }
        return KLinePane(
            id = id,
            kind = kind,
            order = value.optInt("order", 0),
            weight = value.optDouble("weight", if (kind == KLinePaneKind.PRICE) 3.0 else 1.0),
            minHeight = value.optDouble("minHeight", if (kind == KLinePaneKind.PRICE) 120.0 else 60.0),
            state = KLinePaneState.valueOf(value.optString("state", "normal").uppercase()),
            yAxes = axes,
        )
    }

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
        val params = value.optJSONArray("params")?.let { array ->
            List(array.length()) { array.optDouble(it) }
        } ?: template.defaultParams
        return template.instance(
            id = value.optString("id"),
            paneId = value.optString("paneId", "price"),
            params = params,
            precision = value.optInt("precision", 2),
            visible = value.optBoolean("visible", true),
        )
    }

    private fun parseOverlayConfig(value: JSONObject): KLineOverlayConfig {
        val points = value.optJSONArray("points")?.let { array ->
            List(array.length()) { index ->
                array.optJSONObject(index)!!.let { KLineOverlayPoint(it.optLong("timestamp"), it.optDouble("value")) }
            }
        }.orEmpty()
        val styles = linkedMapOf<String, KLineOverlayFigureStyle>()
        value.optJSONObject("styles")?.let { json ->
            json.keys().forEach { key ->
                val style = json.optJSONObject(key) ?: return@forEach
                val dash = style.optJSONArray("lineDash")?.let { array ->
                    List(array.length()) { array.optDouble(it) }
                }.orEmpty()
                styles[key] = KLineOverlayFigureStyle(
                    color = style.optString("color").ifBlank { "#2F80ED" },
                    lineWidth = style.optDouble("lineWidth", 1.0),
                    lineDash = dash,
                    textSize = style.optDouble("textSize", 12.0),
                )
            }
        }
        val extendData = linkedMapOf<String, String>()
        value.optJSONObject("extendData")?.let { json ->
            json.keys().forEach { key -> extendData[key] = json.optString(key) }
        }
        return KLineOverlayConfig(
            templateName = canonicalizeOverlayTemplateName(value.optString("templateName")),
            groupId = value.optString("groupId").ifBlank { null },
            paneId = value.optString("paneId", "price"),
            points = points,
            visible = value.optBoolean("visible", true),
            locked = value.optBoolean("locked", false),
            magnetMode = magnetMode(value.optString("magnetMode")),
            zIndex = value.optInt("zIndex", 0),
            styles = styles,
            extendData = extendData,
        )
    }

    private fun stateToMap(state: KLineChartState): Map<String, Any?> = mapOf(
        "viewport" to state.viewport?.let {
            mapOf(
                "startIndex" to it.startIndex,
                "endIndex" to it.endIndex,
                "barSpace" to it.barSpace,
                "rightOffset" to it.rightOffset,
                "zoomAnchor" to it.zoomAnchor,
            )
        },
        "panes" to state.panes.map(::paneToMap),
        "indicators" to state.indicatorInstances.map(::indicatorToMap),
        "overlays" to state.overlayInstances.map {
            overlayToMap(
                it.id, it.templateName, it.groupId, it.paneId, it.points, it.visible, it.locked,
                it.magnetMode, it.zIndex, it.styles, it.extendData,
            )
        },
        "theme" to if (state.theme == KLineTheme.DARK) "dark" else "light",
    )

    private fun parseState(value: JSONObject): KLineChartState {
        val viewport = value.optJSONObject("viewport")?.let {
            KLineViewport(
                it.optDouble("startIndex"),
                it.optDouble("endIndex"),
                it.optDouble("barSpace"),
                it.optDouble("rightOffset"),
                if (it.has("zoomAnchor")) it.optDouble("zoomAnchor") else null,
            )
        }
        val panes = value.optJSONArray("panes")?.let { array ->
            List(array.length()) { parsePane(array.optJSONObject(it)!!) }
        }.orEmpty()
        val indicators = value.optJSONArray("indicators")?.let { array ->
            List(array.length()) { parseIndicator(array.optJSONObject(it)!!) }
        }.orEmpty()
        val overlays = value.optJSONArray("overlays")?.let { array ->
            List(array.length()) { index ->
                val item = array.optJSONObject(index)!!
                parseOverlayConfig(item).toInstance(item.optString("id"))
            }
        }.orEmpty()
        val theme = if (value.optString("theme").equals("dark", true)) KLineTheme.DARK else KLineTheme.LIGHT
        return KLineChartState(viewport, panes, indicators, overlays, theme)
    }

    private fun paneToMap(pane: KLinePane) = mapOf(
        "id" to pane.id,
        "kind" to pane.kind.name.lowercase(),
        "order" to pane.order,
        "weight" to pane.weight,
        "minHeight" to pane.minHeight,
        "state" to pane.state.name.lowercase(),
        "yAxes" to pane.yAxes.map {
            mapOf(
                "id" to it.id,
                "minValue" to it.minValue,
                "maxValue" to it.maxValue,
                "mode" to it.mode.name.lowercase(),
                "referenceValue" to it.referenceValue,
                "autoScale" to it.autoScale,
            )
        },
    )

    private fun indicatorToMap(value: KLineIndicatorInstance) = mapOf(
        "id" to value.id,
        "templateName" to value.templateName,
        "paneId" to value.paneId,
        "params" to value.params,
        "precision" to value.precision,
        "visible" to value.visible,
    )

    private fun overlayToMap(
        id: String,
        templateName: String,
        groupId: String?,
        paneId: String,
        points: List<KLineOverlayPoint>,
        visible: Boolean,
        locked: Boolean,
        magnetMode: KLineOverlayMagnetMode,
        zIndex: Int,
        styles: Map<String, KLineOverlayFigureStyle>,
        extendData: Map<String, String>,
    ) = mapOf(
        "id" to id,
        "templateName" to templateName,
        "groupId" to groupId,
        "paneId" to paneId,
        "points" to points.map { mapOf("timestamp" to it.timestamp, "value" to it.value) },
        "visible" to visible,
        "locked" to locked,
        "magnetMode" to magnetMode.name.lowercase(),
        "zIndex" to zIndex,
        "styles" to styles.mapValues { (_, it) ->
            mapOf(
                "color" to it.color,
                "lineWidth" to it.lineWidth,
                "lineDash" to it.lineDash,
                "textSize" to it.textSize,
            )
        },
        "extendData" to extendData,
    )

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
                    it.name.equals(value.optString("template"), ignoreCase = true)
                } ?: continue
                val paramsJson = value.optJSONArray("params")
                val params = if (paramsJson == null) {
                    template.defaultParams
                } else {
                    List(paramsJson.length()) { parameterIndex -> paramsJson.optDouble(parameterIndex) }
                }
                controller.addIndicator(
                    template.instance(
                        id = id,
                        paneId = value.optString("paneId", "price"),
                        params = params,
                    ),
                )
                configuredIndicatorIds += id
            }
        }
        onInvalidate()
    }

    private fun periodUnit(value: String): KLinePeriodUnit = when (value.lowercase()) {
        "minute" -> KLinePeriodUnit.MINUTE
        "hour" -> KLinePeriodUnit.HOUR
        "week" -> KLinePeriodUnit.WEEK
        "month" -> KLinePeriodUnit.MONTH
        else -> KLinePeriodUnit.DAY
    }

    private fun magnetMode(value: String): KLineOverlayMagnetMode = when (value.lowercase()) {
        "weak" -> KLineOverlayMagnetMode.WEAK
        "strong" -> KLineOverlayMagnetMode.STRONG
        else -> KLineOverlayMagnetMode.NONE
    }

    private fun mapToJson(value: Map<String, Any?>): JSONObject = JSONObject().apply {
        value.forEach { (key, item) -> put(key, anyToJson(item)) }
    }

    private fun anyToJson(value: Any?): Any? = when (value) {
        null -> null
        is Number, is Boolean, is String -> value
        is Map<*, *> -> {
            val map = linkedMapOf<String, Any?>()
            value.forEach { (k, v) -> if (k != null) map[k.toString()] = v }
            mapToJson(map)
        }
        is List<*> -> JSONArray().apply { value.forEach { put(anyToJson(it)) } }
        else -> value.toString()
    }

    private fun primitiveJson(value: KLineDrawingPrimitive): JSONObject = JSONObject().apply {
        put("layer", value.layer.order)
        when (value) {
            is KLineDrawingPrimitive.Line -> {
                put("type", "line"); put("x1", value.start.x); put("y1", value.start.y)
                put("x2", value.end.x); put("y2", value.end.y); put("color", value.stroke.color)
                put("width", value.stroke.width)
                put("dash", JSONArray().apply { value.stroke.dash.forEach { put(it) } })
            }
            is KLineDrawingPrimitive.Rect -> {
                put("type", "rect"); put("left", value.bounds.left); put("top", value.bounds.top)
                put("right", value.bounds.right); put("bottom", value.bounds.bottom)
                put("color", value.fillColor)
                put("strokeColor", value.stroke?.color); put("strokeWidth", value.stroke?.width ?: 0.0)
            }
            is KLineDrawingPrimitive.Polyline -> {
                put("type", "polyline"); put("color", value.stroke.color); put("width", value.stroke.width)
                put("dash", JSONArray().apply { value.stroke.dash.forEach { put(it) } })
                put("points", JSONArray().apply {
                    value.points.forEach { point ->
                        put(JSONObject().apply { put("x", point.x); put("y", point.y) })
                    }
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
}
