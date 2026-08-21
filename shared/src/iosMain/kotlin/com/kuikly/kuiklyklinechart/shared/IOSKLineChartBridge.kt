package com.kuikly.kuiklyklinechart.shared

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
import com.tencent.kuiklybase.kline.host.KLineChartHostView
import com.tencent.kuiklybase.kline.host.canvas.KLineCanvasAdapter
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayConfig
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import com.tencent.kuiklybase.kline.signal.KLineSignal
import com.tencent.kuiklybase.kline.signal.KLineSignalType
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import com.tencent.kuiklybase.kline.view.KLineChartBindingRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.experimental.ExperimentalObjCName
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.native.ObjCName

/**
 * iOS Kuikly 自定义视图 `KRKLineChart` 的 Kotlin 侧中介。
 *
 * Kuikly iOS 通过 `NSClassFromString("KRKLineChart")` 按类名发现原生视图，
 * 该 OC/Swift 视图类只负责协议实现、手势与绘制，其余全部属性/方法/事件逻辑
 * 在本类完成（与 Android `AndroidKLineChartView` 行为对齐）。
 *
 * 事件通过 [onEvent] 回传 OC/Swift 侧，再路由到 Kuikly 绑定的 callback。
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("KLCChartBridge", exact = true)
class IOSKLineChartBridge(
    private val onInvalidate: () -> Unit,
    private val onEvent: (event: String, params: Map<String, Any?>) -> Unit,
) {
    private val mainScope = MainScope()
    private val json = Json { ignoreUnknownKeys = true }

    private var controller = KLineChartController()
    private var symbol = KLineSymbol("00700", "Demo Stock")
    private var period = KLinePeriod(1, KLinePeriodUnit.DAY)
    private var previousSnapshot: KLineStoreSnapshot? = null
    private var lastCrosshair: com.tencent.kuiklybase.kline.interaction.KLineCrosshair? = null
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
    private var lastPaneHeaderTops: List<Double> = emptyList()
    private var innerView = createInnerView(currentDataSource)

    private fun createInnerView(dataSource: KLineDataSource) = KLineChartHostView(
        dataSource = dataSource,
        controller = controller,
        onInvalidate = { postInvalidate() },
        onSnapshot = { snapshot ->
            dispatchSnapshotEvents(previousSnapshot, snapshot)
            previousSnapshot = snapshot
            val crosshair = snapshot.crosshair
            if (crosshair != lastCrosshair) {
                lastCrosshair = crosshair
                postEvent("onCrosshairChange",
                    if (crosshair == null) emptyMap() else mapOf(
                        "timestamp" to crosshair.timestamp,
                        "price" to crosshair.value,
                        "paneId" to crosshair.paneId,
                    ))
            }
        },
    ) {
        addDefaultIndicators()
        onError { error ->
            postEvent("onError", mapOf("code" to error.code.name, "message" to error.message))
        }
    }.also { view ->
        view.attach()
        view.engine.setMode(configuredMode)
        view.engine.setPriceStyle(configuredPriceStyle)
        view.engine.setSignals(configuredSignals)
    }

    init {
        controller.setMarket(symbol, period)
    }

    /* ------------------------------- 属性 ------------------------------- */

    fun setProp(propKey: String, propValue: String): Boolean = try {
        setKLineProp(propKey, propValue)
    } catch (error: Exception) {
        val message = "Invalid $propKey property: ${error.message ?: error::class.simpleName}"
        if (pendingBridgeError == null && !hasEventListener("onError")) {
            pendingBridgeError = message
        } else {
            emitBridgeError(message)
        }
        true
    }

    /** OC 侧在事件 callback 绑定后调用，让 pending 错误得以透出。 */
    fun onEventCallbackBound(event: String) {
        if (event == "onError") {
            pendingBridgeError?.let(::emitBridgeError)
            pendingBridgeError = null
        }
        if (event == "onPaneLayoutChange") {
            // 窗格布局只在变化时上报一次，绑定晚于首帧绘制时会丢事件，重置缓存并重绘补发
            lastPaneHeaderTops = emptyList()
            postInvalidate()
        }
    }

    private var eventNames: MutableSet<String> = linkedSetOf()

    /** 由 OC 侧同步当前已绑定的事件名集合（用于 pending 错误判断等）。 */
    fun setBoundEventNames(names: List<String>) {
        eventNames = names.toMutableSet()
    }

    private fun hasEventListener(name: String): Boolean = name in eventNames

    private fun setKLineProp(propKey: String, propValue: String): Boolean = when (propKey) {
        "bindingId" -> {
            KLineChartBindingRegistry.take(propValue)?.let { binding ->
                replaceDataSource(binding.dataSource, binding.controller)
            } ?: emitBridgeError("Unknown or expired bindingId")
            true
        }
        "symbol" -> {
            val value = json.parseToJsonElement(propValue).jsonObject
            symbol = KLineSymbol(value.str("ticker"), value.str("name"))
            controller.setMarket(symbol, period)
            true
        }
        "period" -> {
            val value = json.parseToJsonElement(propValue).jsonObject
            period = KLinePeriod(value.int("value") ?: 1, periodUnit(value.str("unit")))
            controller.setMarket(symbol, period)
            true
        }
        "theme" -> {
            configuredTheme = if (propValue == "dark") KLineTheme.DARK else KLineTheme.LIGHT
            controller.setTheme(configuredTheme)
            true
        }
        "mode" -> {
            configuredMode = if (propValue.equals("compact", true)) KLineChartMode.COMPACT else KLineChartMode.FULL
            innerView.engine.setMode(configuredMode)
            postInvalidate()
            true
        }
        "priceStyle" -> {
            configuredPriceStyle = if (propValue.equals("line", true)) KLinePriceStyle.LINE else KLinePriceStyle.CANDLE
            innerView.engine.setPriceStyle(configuredPriceStyle)
            postInvalidate()
            true
        }
        "bars" -> {
            runBridgeUpdate("bars") { replaceBars(parseBars(propValue)) }
            true
        }
        "signals" -> {
            runBridgeUpdate("signals") {
                configuredSignals = parseSignals(propValue)
                innerView.engine.setSignals(configuredSignals)
                postInvalidate()
            }
            true
        }
        "config" -> {
            runBridgeUpdate("config") {
                applyConfig(json.parseToJsonElement(propValue).jsonObject)
                configuredConfig = propValue
            }
            true
        }
        else -> false
    }

    /* ------------------------------- 方法 ------------------------------- */

    /** 返回值仅用于带回调的方法（createOverlay / exportState），其余返回 null。 */
    fun callMethod(method: String, params: String?): Map<String, Any?>? {
        return try {
            val value = params?.let { json.parseToJsonElement(it).jsonObject }
            when (method) {
                "scrollToLatest" -> { controller.scrollToLatest(); null }
                "zoom" -> { controller.zoom(value?.dbl("factor") ?: 1.0); null }
                "scrollToTimestamp" -> { controller.scrollToTimestamp(value?.lng("timestamp") ?: 0L); null }
                "scrollByBars" -> { controller.scrollByBars(value?.dbl("count") ?: 0.0); null }
                "zoomAtTimestamp" -> {
                    controller.zoomAtTimestamp(value?.dbl("factor") ?: 1.0, value?.lng("timestamp") ?: 0L); null
                }
                "loadBefore" -> { innerView.engine.triggerLoadBefore(); null }
                "loadAfter" -> { innerView.engine.triggerLoadAfter(); null }
                "retryInitialLoad" -> { innerView.engine.retryInitialLoad(); null }
                "resetViewport" -> { controller.resetViewport(); null }
                "beginOverlay" -> {
                    controller.beginOverlay(
                        value?.str("templateName") ?: "",
                        value?.str("paneId") ?: "price",
                        magnetMode(value?.str("magnetMode")),
                    ); null
                }
                "cancelInteraction" -> { controller.cancelInteraction(); null }
                "clearCrosshair" -> { controller.clearCrosshair(); null }
                "deleteSelectedOverlay" -> { controller.deleteSelectedOverlay(); null }
                "setPane" -> { controller.setPane(parsePane(value)); null }
                "removePane" -> { controller.removePane(value?.str("paneId") ?: ""); null }
                "movePane" -> {
                    controller.movePane(value?.str("paneId") ?: "", value?.int("index") ?: 0); null
                }
                "setPaneState" -> {
                    controller.setPaneState(
                        value?.str("paneId") ?: "",
                        KLinePaneState.valueOf((value?.str("state") ?: "normal").uppercase()),
                    ); null
                }
                "addIndicator" -> { controller.addIndicator(parseIndicator(value)); null }
                "updateIndicator" -> { controller.updateIndicator(parseIndicator(value)); null }
                "removeIndicator" -> { controller.removeIndicator(value?.str("id") ?: ""); null }
                "createOverlay" -> {
                    val id = controller.createOverlay(parseOverlayConfig(value))
                    mapOf("id" to id)
                }
                "updateOverlay" -> {
                    controller.updateOverlay(value?.str("id") ?: "", parseOverlayConfig(value)); null
                }
                "removeOverlay" -> { controller.removeOverlay(value?.str("id") ?: ""); null }
                "exportState" -> stateToMap(controller.exportState())
                "restoreState" -> { controller.restoreState(parseState(value)); null }
                else -> null
            }.also {
                postInvalidate()
            }
        } catch (error: Exception) {
            val message = "Invalid $method arguments: ${error.message ?: error::class.simpleName}"
            emitBridgeError(message)
            mapOf("error" to message)
        }
    }

    /* ------------------------------ 生命周期 ------------------------------ */

    fun onSizeChanged(width: Int, height: Int) {
        innerView.onSizeChanged(width, height)
    }

    fun draw(canvas: KLineCanvasAdapter) {
        innerView.draw(canvas)
        val panes = innerView.latestRenderPlan()?.panes.orEmpty()
        // headerRect 为像素坐标，Kuikly 侧 top() 以 point 为单位（对齐 Android 的 /density）
        val tops = listOf("price", "first", "second").map { paneId ->
            panes.firstOrNull { it.id == paneId }?.headerRect?.top?.div(displayScale) ?: 0.0
        }
        if (tops != lastPaneHeaderTops) {
            lastPaneHeaderTops = tops
            postEvent(
                "onPaneLayoutChange",
                mapOf(
                    "priceTop" to (tops.getOrNull(0) ?: 0.0),
                    "firstTop" to (tops.getOrNull(1) ?: 0.0),
                    "secondTop" to (tops.getOrNull(2) ?: 0.0),
                ),
            )
        }
    }

    fun viewDidDisappear() {
        detachedState = runCatching { controller.exportState() }.getOrNull()
        innerView.dispose()
    }

    fun viewWillAppear(width: Int, height: Int) {
        if (detachedState != null) {
            innerView = createInnerView(currentDataSource)
            if (width > 0 && height > 0) innerView.onSizeChanged(width, height)
            controller.setMarket(symbol, period)
            controller.setTheme(configuredTheme)
            configuredConfig?.let { runCatching { applyConfig(json.parseToJsonElement(it).jsonObject) } }
            detachedState?.let(controller::restoreState)
            detachedState = null
            postInvalidate()
        }
    }

    /* ------------------------------- 手势 ------------------------------- */

    fun pointerDown(x: Double, y: Double) = dispatch(KLinePointerEvent.Down(x, y))
    fun pointerSecondaryDown(x: Double, y: Double) = dispatch(KLinePointerEvent.SecondaryDown(x, y))
    fun pointerMove(x: Double, y: Double, scaleFactor: Double, pointerCount: Int) =
        dispatch(KLinePointerEvent.Move(x, y, scaleFactor, pointerCount))
    fun pointerUp(x: Double, y: Double) = dispatch(KLinePointerEvent.Up(x, y))
    fun pointerCancel(x: Double, y: Double) = dispatch(KLinePointerEvent.Cancel(x, y))
    fun pointerTap(x: Double, y: Double) = dispatch(KLinePointerEvent.Tap(x, y))
    fun pointerLongPress(x: Double, y: Double) = dispatch(KLinePointerEvent.LongPress(x, y))
    fun resetViewport() {
        controller.resetViewport()
        postInvalidate()
    }

    private fun dispatch(event: KLinePointerEvent) {
        val outcome = innerView.onPointerEvent(event)
        if (outcome is KLinePointerDispatchOutcome.SignalClick) {
            val signal = outcome.signal
            postEvent("onSignalClick", mapOf("id" to signal.id, "title" to signal.title, "summary" to signal.summary))
        }
        postInvalidate()
    }

    /* --------------------------- 窗格标题命中测试 --------------------------- */

    /** 命中则返回窗格 id，否则 null。x/y 为像素坐标（已乘屏幕缩放）。 */
    fun paneHeaderAt(x: Double, y: Double): String? {
        if (x !in 0.0..(76.0 * displayScale)) return null
        return innerView.latestRenderPlan()?.panes.orEmpty().firstOrNull { pane ->
            val header = pane.headerRect ?: return@firstOrNull false
            y in header.top..header.bottom
        }?.id
    }

    /** 由 OC 侧在视图初始化时设置，用于窗格标题命中宽度换算。 */
    var displayScale: Double = 3.0

    /* ------------------------------- 内部 ------------------------------- */

    private fun postInvalidate() {
        mainScope.launch(Dispatchers.Main) { onInvalidate() }
    }

    private fun postEvent(event: String, params: Map<String, Any?>) {
        mainScope.launch(Dispatchers.Main) { onEvent(event, params) }
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
        controller.setMarket(symbol, period)
        controller.setTheme(configuredTheme)
        configuredConfig?.let { runCatching { applyConfig(json.parseToJsonElement(it).jsonObject) } }
        state?.let(controller::restoreState)
        postInvalidate()
    }

    private fun dispatchSnapshotEvents(previous: KLineStoreSnapshot?, current: KLineStoreSnapshot) {
        if (previous?.viewport != current.viewport) current.viewport?.let { viewport ->
            postEvent("onVisibleRangeChange", mapOf("startIndex" to floor(viewport.startIndex).toInt(), "endIndex" to ceil(viewport.endIndex).toInt()))
        }
        if (previous?.clickSelection != current.clickSelection) current.clickSelection?.let { selected ->
            postEvent("onBarClick", mapOf("timestamp" to selected.timestamp, "index" to selected.index))
        }
        if (previous?.loadState != current.loadState) postEvent(
            "onLoadStateChange",
            mapOf(
                "initial" to current.loadState.initial.name.lowercase(),
                "before" to current.loadState.before.name.lowercase(),
                "after" to current.loadState.after.name.lowercase(),
            ),
        )
        if (previous?.selectedOverlayId != current.selectedOverlayId) postEvent("onOverlayClick", mapOf("id" to current.selectedOverlayId))
        if (previous?.overlayRevision != current.overlayRevision) postEvent("onOverlayChange", mapOf("revision" to current.overlayRevision))
        if (previous?.period != current.period) current.period?.let { value ->
            postEvent("onPeriodChange", mapOf("value" to value.span, "unit" to value.unit.name.lowercase()))
        }
        if (previous?.indicatorRevision != current.indicatorRevision) postEvent(
            "onIndicatorChange",
            mapOf("ids" to current.indicatorInstances.joinToString(",") { it.id }),
        )
    }

    private fun runBridgeUpdate(property: String, update: () -> Unit) {
        try {
            update()
        } catch (error: Exception) {
            val message = "Invalid $property JSON: ${error.message ?: error::class.simpleName}"
            if (pendingBridgeError == null && !hasEventListener("onError")) {
                pendingBridgeError = message
            } else {
                emitBridgeError(message)
            }
        }
    }

    private fun emitBridgeError(message: String) {
        postEvent("onError", mapOf("code" to "INVALID_ARGUMENT", "message" to message))
    }

    private fun resetConfiguredComponents() {
        configuredPaneIds.clear()
        configuredPaneIds += listOf("price", "volume")
        configuredIndicatorIds.clear()
        configuredIndicatorIds += listOf("ma-price", "ma-price-10", "vol")
    }

    /* ------------------------------- 解析 ------------------------------- */

    private val JsonElement.jsonObject: JsonObject
        get() = this as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject?.opt(name: String): JsonElement? = this?.get(name)

    private fun JsonObject?.str(name: String): String = (opt(name) as? JsonPrimitive)?.content ?: ""
    private fun JsonObject?.int(name: String): Int? = (opt(name) as? JsonPrimitive)?.intOrNull
    private fun JsonObject?.lng(name: String): Long? = (opt(name) as? JsonPrimitive)?.longOrNull
    private fun JsonObject?.dbl(name: String): Double? = (opt(name) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject?.bool(name: String): Boolean? = (opt(name) as? JsonPrimitive)?.booleanOrNull

    private fun parseBars(jsonText: String): List<KLineBar> {
        val array = json.parseToJsonElement(jsonText) as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            KLineBar(
                timestamp = obj.lng("timestamp") ?: 0L,
                open = obj.dbl("open") ?: 0.0,
                high = obj.dbl("high") ?: 0.0,
                low = obj.dbl("low") ?: 0.0,
                close = obj.dbl("close") ?: 0.0,
                volume = obj.dbl("volume") ?: 0.0,
                turnover = obj.dbl("turnover") ?: 0.0,
            )
        }
    }

    private fun parseSignals(jsonText: String): List<KLineSignal> {
        val array = json.parseToJsonElement(jsonText) as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            KLineSignal(
                id = obj.str("id"),
                timestamp = obj.lng("timestamp") ?: 0L,
                value = obj.dbl("value") ?: 0.0,
                type = KLineSignalType.valueOf((obj.str("type").ifBlank { "INFO" }).uppercase()),
                title = obj.str("title"),
                summary = obj.str("summary"),
                confidence = obj.dbl("confidence"),
            )
        }
    }

    private fun parsePane(value: JsonObject?): KLinePane {
        val id = value?.str("id") ?: ""
        val kind = if (value.str("kind").equals("price", true)) KLinePaneKind.PRICE else KLinePaneKind.INDICATOR
        val axes = (value?.get("yAxes") as? JsonArray)?.mapIndexed { index, axis ->
            parseYAxis(axis as? JsonObject, "$id-y-$index")
        }.orEmpty().ifEmpty { listOf(KLineYAxis("$id-y", 0.0, 100.0, autoScale = true)) }
        return KLinePane(
            id = id,
            kind = kind,
            order = value?.int("order") ?: 0,
            weight = value?.dbl("weight") ?: if (kind == KLinePaneKind.PRICE) 3.0 else 1.0,
            minHeight = value?.dbl("minHeight") ?: if (kind == KLinePaneKind.PRICE) 120.0 else 60.0,
            state = KLinePaneState.valueOf((value?.str("state").orEmpty().ifBlank { "normal" }).uppercase()),
            yAxes = axes,
        )
    }

    private fun parseYAxis(value: JsonObject?, fallbackId: String) = KLineYAxis(
        id = value?.str("id").orEmpty().ifBlank { fallbackId },
        minValue = value?.dbl("minValue") ?: 0.0,
        maxValue = value?.dbl("maxValue") ?: 100.0,
        mode = KLineYAxisMode.valueOf((value?.str("mode").orEmpty().ifBlank { "normal" }).uppercase()),
        referenceValue = value?.dbl("referenceValue"),
        autoScale = value?.bool("autoScale") ?: true,
    )

    private fun parseIndicator(value: JsonObject?): KLineIndicatorInstance {
        val templateName = value?.str("templateName").orEmpty().ifBlank { value?.str("template") ?: "" }
        val template = KLineBuiltInIndicators.templates.firstOrNull { it.name.equals(templateName, true) }
            ?: error("Unknown indicator template: $templateName")
        val params = (value?.get("params") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            ?: template.defaultParams
        return template.instance(
            id = value?.str("id") ?: "",
            paneId = value?.str("paneId").orEmpty().ifBlank { "price" },
            params = params,
            precision = value?.int("precision") ?: 2,
            visible = value?.bool("visible") ?: true,
        )
    }

    private fun parseOverlayConfig(value: JsonObject?): KLineOverlayConfig {
        val points = (value?.get("points") as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            KLineOverlayPoint(obj.lng("timestamp") ?: 0L, obj.dbl("value") ?: 0.0)
        }.orEmpty()
        val styles = linkedMapOf<String, KLineOverlayFigureStyle>()
        (value?.get("styles") as? JsonObject)?.forEach { (key, styleValue) ->
            val style = styleValue as? JsonObject ?: return@forEach
            val dash = (style.get("lineDash") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()
            styles[key] = KLineOverlayFigureStyle(
                color = style.str("color").ifBlank { "#2F80ED" },
                lineWidth = style.dbl("lineWidth") ?: 1.0,
                lineDash = dash,
                textSize = style.dbl("textSize") ?: 12.0,
            )
        }
        val extendData = linkedMapOf<String, String>()
        (value?.get("extendData") as? JsonObject)?.forEach { (key, item) ->
            extendData[key] = (item as? JsonPrimitive)?.content ?: ""
        }
        return KLineOverlayConfig(
            templateName = value?.str("templateName") ?: "",
            groupId = value?.str("groupId").orEmpty().ifBlank { null },
            paneId = value?.str("paneId").orEmpty().ifBlank { "price" },
            points = points,
            visible = value?.bool("visible") ?: true,
            locked = value?.bool("locked") ?: false,
            magnetMode = magnetMode(value?.str("magnetMode")),
            zIndex = value?.int("zIndex") ?: 0,
            styles = styles,
            extendData = extendData,
        )
    }

    private fun stateToMap(state: KLineChartState): Map<String, Any?> = mapOf(
        "viewport" to state.viewport?.let {
            mapOf("startIndex" to it.startIndex, "endIndex" to it.endIndex, "barSpace" to it.barSpace, "rightOffset" to it.rightOffset, "zoomAnchor" to it.zoomAnchor)
        },
        "panes" to state.panes.map(::paneToMap),
        "indicators" to state.indicatorInstances.map(::indicatorToMap),
        "overlays" to state.overlayInstances.map { overlayToMap(it.id, it.templateName, it.groupId, it.paneId, it.points, it.visible, it.locked, it.magnetMode, it.zIndex, it.styles, it.extendData) },
        "theme" to if (state.theme == KLineTheme.DARK) "dark" else "light",
    )

    private fun parseState(value: JsonObject?): KLineChartState {
        val viewport = value?.get("viewport") as? JsonObject
        val panes = (value?.get("panes") as? JsonArray)?.mapNotNull { parsePane(it as? JsonObject) }.orEmpty()
        val indicators = (value?.get("indicators") as? JsonArray)?.mapNotNull { runCatching { parseIndicator(it as? JsonObject) }.getOrNull() }.orEmpty()
        val overlays = (value?.get("overlays") as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            parseOverlayConfig(obj).toInstance(obj.str("id"))
        }.orEmpty()
        val theme = if (value.str("theme").equals("dark", true)) KLineTheme.DARK else KLineTheme.LIGHT
        return KLineChartState(
            viewport?.let {
                com.tencent.kuiklybase.kline.viewport.KLineViewport(
                    it.dbl("startIndex") ?: 0.0,
                    it.dbl("endIndex") ?: 0.0,
                    it.dbl("barSpace") ?: 0.0,
                    it.dbl("rightOffset") ?: 0.0,
                    it.dbl("zoomAnchor"),
                )
            },
            panes,
            indicators,
            overlays,
            theme,
        )
    }

    private fun paneToMap(pane: KLinePane): Map<String, Any?> = mapOf(
        "id" to pane.id,
        "kind" to pane.kind.name.lowercase(),
        "order" to pane.order,
        "weight" to pane.weight,
        "minHeight" to pane.minHeight,
        "state" to pane.state.name.lowercase(),
        "yAxes" to pane.yAxes.map {
            mapOf("id" to it.id, "minValue" to it.minValue, "maxValue" to it.maxValue, "mode" to it.mode.name.lowercase(), "referenceValue" to it.referenceValue, "autoScale" to it.autoScale)
        },
    )

    private fun indicatorToMap(value: KLineIndicatorInstance): Map<String, Any?> = mapOf(
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
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "templateName" to templateName,
        "groupId" to groupId,
        "paneId" to paneId,
        "points" to points.map { mapOf("timestamp" to it.timestamp, "value" to it.value) },
        "visible" to visible,
        "locked" to locked,
        "magnetMode" to magnetMode.name.lowercase(),
        "zIndex" to zIndex,
        "styles" to styles.mapValues { (_, it) -> mapOf("color" to it.color, "lineWidth" to it.lineWidth, "lineDash" to it.lineDash, "textSize" to it.textSize) },
        "extendData" to extendData,
    )

    private fun periodUnit(value: String): KLinePeriodUnit = when (value.lowercase()) {
        "minute" -> KLinePeriodUnit.MINUTE
        "hour" -> KLinePeriodUnit.HOUR
        "week" -> KLinePeriodUnit.WEEK
        "month" -> KLinePeriodUnit.MONTH
        else -> KLinePeriodUnit.DAY
    }

    private fun magnetMode(value: String?): KLineOverlayMagnetMode = when (value?.lowercase()) {
        "weak" -> KLineOverlayMagnetMode.WEAK
        "strong" -> KLineOverlayMagnetMode.STRONG
        else -> KLineOverlayMagnetMode.NONE
    }

    private fun applyConfig(config: JsonObject?) {
        configuredIndicatorIds.forEach(controller::removeIndicator)
        configuredPaneIds.forEach(controller::removePane)
        configuredIndicatorIds.clear()
        configuredPaneIds.clear()

        (config?.get("panes") as? JsonArray)?.forEachIndexed { index, paneValue ->
            val value = paneValue as? JsonObject ?: return@forEachIndexed
            val id = value.str("id")
            if (id.isBlank()) return@forEachIndexed
            // 与 setPane/restoreState 共用 parsePane：统一解析 yAxes（含 min/max/referenceValue/autoScale）与 state
            val pane = parsePane(value).let { copy ->
                if (value.opt("order") != null) copy else copy.copy(order = index)
            }
            controller.setPane(pane)
            configuredPaneIds += id
        }
        (config?.get("indicators") as? JsonArray)?.forEach { indicatorValue ->
            val value = indicatorValue as? JsonObject ?: return@forEach
            val id = value.str("id")
            val template = KLineBuiltInIndicators.templates.firstOrNull {
                it.name.equals(value.str("template"), ignoreCase = true)
            } ?: return@forEach
            val paramsJson = value.get("params") as? JsonArray
            val params = if (paramsJson == null) {
                template.defaultParams
            } else {
                paramsJson.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            }
            controller.addIndicator(
                template.instance(
                    id = id,
                    paneId = value.str("paneId").ifBlank { "price" },
                    params = params,
                ),
            )
            configuredIndicatorIds += id
        }
        postInvalidate()
    }
}
