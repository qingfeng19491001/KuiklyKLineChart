package com.tencent.kuiklybase.kline.host

import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
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
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.data.KLineDataSource
import com.tencent.kuiklybase.kline.view.KLineChartBindingRegistry
import com.tencent.kuiklybase.kline.host.KLineChartHostView
import com.tencent.kuiklybase.kline.host.canvas.AndroidKLineCanvasAdapter
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
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import org.json.JSONArray
import org.json.JSONObject

class AndroidKLineChartView(context: Context) : View(context), IKuiklyRenderViewExport {
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false
    private var controller = KLineChartController()
    private var symbol = KLineSymbol("00700", "Demo Stock")
    private var period = KLinePeriod(1, KLinePeriodUnit.DAY)
    private var errorCallback: KuiklyRenderCallback? = null
    private var crosshairCallback: KuiklyRenderCallback? = null
    private var signalClickCallback: KuiklyRenderCallback? = null
    private var paneLayoutCallback: KuiklyRenderCallback? = null
    private var paneHeaderClickCallback: KuiklyRenderCallback? = null
    private var pressedPaneHeaderId: String? = null
    private var lastPaneHeaderTops: List<Float> = emptyList()
    private val snapshotCallbacks = mutableMapOf<String, KuiklyRenderCallback>()
    private var previousSnapshot: com.tencent.kuiklybase.kline.store.KLineStoreSnapshot? = null
    private var lastCrosshair: com.tencent.kuiklybase.kline.interaction.KLineCrosshair? = null
    private val configuredPaneIds = linkedSetOf("price", "volume")
    private val configuredIndicatorIds = linkedSetOf("ma-price", "ma-price-10", "vol")
    private val density = resources.displayMetrics.density
    private var configuredMode = KLineChartMode.FULL
    private var configuredPriceStyle = KLinePriceStyle.CANDLE
    private var configuredTheme = KLineTheme.LIGHT
    private var configuredSignals: List<KLineSignal> = emptyList()
    private var configuredConfig: String? = null
    private var pendingBridgeError: String? = null
    private var innerView = createInnerView(StaticKLineDataSource(emptyList()))

    private fun createInnerView(dataSource: KLineDataSource) = KLineChartHostView(
        dataSource = dataSource,
        controller = controller,
        onInvalidate = { postInvalidateOnAnimation() },
        onSnapshot = { snapshot ->
            dispatchSnapshotEvents(previousSnapshot, snapshot)
            previousSnapshot = snapshot
            val crosshair = snapshot.crosshair
            if (crosshair != lastCrosshair) {
                lastCrosshair = crosshair
                post {
                    crosshairCallback?.invoke(
                        if (crosshair == null) emptyMap() else mapOf(
                            "timestamp" to crosshair.timestamp,
                            "price" to crosshair.value,
                            "paneId" to crosshair.paneId,
                        ),
                    )
                }
            }
        },
    ) {
        addDefaultIndicators()
        onError { error ->
            post {
                errorCallback?.invoke(mapOf("code" to error.code.name, "message" to error.message))
            }
        }
    }.also { view ->
        view.attach()
        view.engine.setMode(configuredMode)
        view.engine.setPriceStyle(configuredPriceStyle)
        view.engine.setSignals(configuredSignals)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            dispatch(KLinePointerEvent.Down(e.x.toDouble(), e.y.toDouble()))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            controller.resetViewport()
            postInvalidateOnAnimation()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return true
            dispatch(KLinePointerEvent.Move(e2.x.toDouble(), e2.y.toDouble(), pointerCount = e2.pointerCount))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            dispatch(KLinePointerEvent.LongPress(e.x.toDouble(), e.y.toDouble()))
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var accumulatedFactor = 1.0

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            accumulatedFactor = 1.0
            dispatch(KLinePointerEvent.Cancel(detector.focusX.toDouble(), detector.focusY.toDouble()))
            dispatch(KLinePointerEvent.SecondaryDown(detector.focusX.toDouble(), detector.focusY.toDouble()))
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            accumulatedFactor *= detector.scaleFactor.toDouble()
            dispatch(
                KLinePointerEvent.Move(
                    detector.focusX.toDouble(),
                    detector.focusY.toDouble(),
                    accumulatedFactor,
                    2,
                ),
            )
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            dispatch(KLinePointerEvent.Up(detector.focusX.toDouble(), detector.focusY.toDouble()))
        }
    })

    init {
        controller.setMarket(symbol, period)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
        "bindingId" -> {
            KLineChartBindingRegistry.take(propValue as String)?.let { binding ->
                replaceDataSource(binding.dataSource, binding.controller)
            } ?: emitBridgeError("Unknown or expired bindingId")
            true
        }

        "symbol" -> {
            val value = JSONObject(propValue as String)
            symbol = KLineSymbol(value.optString("ticker"), value.optString("name"))
            controller.setMarket(symbol, period)
            true
        }
        "period" -> {
            val value = JSONObject(propValue as String)
            period = KLinePeriod(value.optInt("value", 1), periodUnit(value.optString("unit")))
            controller.setMarket(symbol, period)
            true
        }
        "theme" -> {
            configuredTheme = if (propValue == "dark") KLineTheme.DARK else KLineTheme.LIGHT
            controller.setTheme(configuredTheme)
            true
        }
        "mode" -> {
            configuredMode = if ((propValue as String).equals("compact", true)) KLineChartMode.COMPACT else KLineChartMode.FULL
            innerView.engine.setMode(configuredMode)
            postInvalidateOnAnimation()
            true
        }
        "priceStyle" -> {
            configuredPriceStyle = if ((propValue as String).equals("line", true)) KLinePriceStyle.LINE else KLinePriceStyle.CANDLE
            innerView.engine.setPriceStyle(configuredPriceStyle)
            postInvalidateOnAnimation()
            true
        }
        "bars" -> {
            runBridgeUpdate("bars") { replaceBars(parseBars(propValue as String)) }
            true
        }
        "signals" -> {
            runBridgeUpdate("signals") {
                configuredSignals = parseSignals(propValue as String)
                innerView.engine.setSignals(configuredSignals)
                postInvalidateOnAnimation()
            }
            true
        }
        "config" -> {
            runBridgeUpdate("config") {
                val json = propValue as String
                applyConfig(JSONObject(json))
                configuredConfig = json
            }
            true
        }
        "onError" -> {
            errorCallback = propValue as KuiklyRenderCallback
            pendingBridgeError?.let(::emitBridgeError)
            pendingBridgeError = null
            true
        }
        "onCrosshairChange" -> {
            crosshairCallback = propValue as KuiklyRenderCallback
            true
        }
        "onSignalClick" -> {
            signalClickCallback = propValue as KuiklyRenderCallback
            true
        }
        "onPaneLayoutChange" -> {
            paneLayoutCallback = propValue as KuiklyRenderCallback
            lastPaneHeaderTops = emptyList()
            postInvalidateOnAnimation()
            true
        }
        "onPaneHeaderClick" -> {
            paneHeaderClickCallback = propValue as KuiklyRenderCallback
            true
        }
        "onVisibleRangeChange", "onBarClick", "onLoadStateChange", "onOverlayClick",
        "onOverlayChange", "onPeriodChange", "onIndicatorChange" -> {
            snapshotCallbacks[propKey] = propValue as KuiklyRenderCallback
            true
        }
        else -> super.setProp(propKey, propValue)
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            "scrollToLatest" -> controller.scrollToLatest()
            "zoom" -> controller.zoom(JSONObject(params.orEmpty()).optDouble("factor", 1.0))
            "scrollToTimestamp" -> controller.scrollToTimestamp(JSONObject(params.orEmpty()).optLong("timestamp"))
            "scrollByBars" -> controller.scrollByBars(JSONObject(params.orEmpty()).optDouble("count"))
            "zoomAtTimestamp" -> JSONObject(params.orEmpty()).let { controller.zoomAtTimestamp(it.optDouble("factor", 1.0), it.optLong("timestamp")) }
            "loadBefore" -> innerView.engine.triggerLoadBefore()
            "loadAfter" -> innerView.engine.triggerLoadAfter()
            "retryInitialLoad" -> innerView.engine.retryInitialLoad()
            "resetViewport" -> controller.resetViewport()
            "beginOverlay" -> {
                val value = JSONObject(params.orEmpty())
                controller.beginOverlay(
                    value.optString("templateName"),
                    value.optString("paneId", "price"),
                    magnetMode(value.optString("magnetMode")),
                )
            }
            "cancelInteraction" -> controller.cancelInteraction()
            "clearCrosshair" -> controller.clearCrosshair()
            "deleteSelectedOverlay" -> controller.deleteSelectedOverlay()
            "setPane" -> controller.setPane(parsePane(JSONObject(params.orEmpty())))
            "removePane" -> controller.removePane(JSONObject(params.orEmpty()).getString("paneId"))
            "movePane" -> JSONObject(params.orEmpty()).let { controller.movePane(it.getString("paneId"), it.getInt("index")) }
            "setPaneState" -> JSONObject(params.orEmpty()).let {
                controller.setPaneState(it.getString("paneId"), KLinePaneState.valueOf(it.getString("state").uppercase()))
            }
            "addIndicator" -> controller.addIndicator(parseIndicator(JSONObject(params.orEmpty())))
            "updateIndicator" -> controller.updateIndicator(parseIndicator(JSONObject(params.orEmpty())))
            "removeIndicator" -> controller.removeIndicator(JSONObject(params.orEmpty()).getString("id"))
            "createOverlay" -> {
                val id = controller.createOverlay(parseOverlayConfig(JSONObject(params.orEmpty())))
                callback?.invoke(mapOf("id" to id))
            }
            "updateOverlay" -> JSONObject(params.orEmpty()).let {
                controller.updateOverlay(it.getString("id"), parseOverlayConfig(it))
            }
            "removeOverlay" -> controller.removeOverlay(JSONObject(params.orEmpty()).getString("id"))
            "exportState" -> callback?.invoke(stateToMap(controller.exportState()))
            "restoreState" -> controller.restoreState(parseState(JSONObject(params.orEmpty())))
            else -> return super.call(method, params, callback)
        }
        postInvalidateOnAnimation()
        return null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        innerView.onSizeChanged(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        innerView.draw(AndroidKLineCanvasAdapter(canvas, density))
        val panes = innerView.latestRenderPlan()?.panes.orEmpty()
        val tops = listOf("price", "first", "second").map { paneId ->
            panes.firstOrNull { it.id == paneId }?.headerRect?.top?.toFloat()?.div(density) ?: 0f
        }
        if (tops != lastPaneHeaderTops) {
            lastPaneHeaderTops = tops
            post {
                paneLayoutCallback?.invoke(
                    mapOf(
                        "priceTop" to (tops.getOrNull(0) ?: 0f),
                        "firstTop" to (tops.getOrNull(1) ?: 0f),
                        "secondTop" to (tops.getOrNull(2) ?: 0f),
                    ),
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val headerId = paneHeaderAt(event.x, event.y)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && headerId != null) {
            pressedPaneHeaderId = headerId
            return true
        }
        pressedPaneHeaderId?.let { pressedId ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (headerId == pressedId) {
                        paneHeaderClickCallback?.invoke(mapOf("paneId" to pressedId))
                    }
                    pressedPaneHeaderId = null
                }
                MotionEvent.ACTION_CANCEL -> pressedPaneHeaderId = null
            }
            return true
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - touchDownX) > 12f * density || kotlin.math.abs(event.y - touchDownY) > 12f * density) {
                    touchMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!touchMoved && !scaleDetector.isInProgress) dispatch(KLinePointerEvent.Tap(event.x.toDouble(), event.y.toDouble()))
                dispatch(KLinePointerEvent.Up(event.x.toDouble(), event.y.toDouble()))
            }
            MotionEvent.ACTION_CANCEL -> dispatch(KLinePointerEvent.Cancel(event.x.toDouble(), event.y.toDouble()))
        }
        return true
    }

    override fun onDetachedFromWindow() {
        innerView.dispose()
        errorCallback = null
        crosshairCallback = null
        signalClickCallback = null
        paneLayoutCallback = null
        paneHeaderClickCallback = null
        snapshotCallbacks.clear()
        super.onDetachedFromWindow()
    }

    private fun dispatch(event: KLinePointerEvent) {
        val outcome = innerView.onPointerEvent(event)
        if (outcome is KLinePointerDispatchOutcome.SignalClick) {
            val signal = outcome.signal
            signalClickCallback?.invoke(mapOf("id" to signal.id, "title" to signal.title, "summary" to signal.summary))
        }
        postInvalidateOnAnimation()
    }

    private fun paneHeaderAt(x: Float, y: Float): String? {
        if (x !in 0f..(76f * density)) return null
        return innerView.latestRenderPlan()?.panes.orEmpty().firstOrNull { pane ->
            val header = pane.headerRect ?: return@firstOrNull false
            y.toDouble() in header.top..header.bottom
        }?.id
    }

    private fun replaceBars(bars: List<KLineBar>) {
        replaceDataSource(StaticKLineDataSource(bars), KLineChartController())
    }

    private fun replaceDataSource(dataSource: KLineDataSource, newController: KLineChartController) {
        innerView.dispose()
        controller = newController
        previousSnapshot = null
        resetConfiguredComponents()
        innerView = createInnerView(dataSource)
        if (width > 0 && height > 0) innerView.onSizeChanged(width, height)
        controller.setMarket(symbol, period)
        controller.setTheme(configuredTheme)
        configuredConfig?.let { applyConfig(JSONObject(it)) }
        postInvalidateOnAnimation()
    }

    private fun dispatchSnapshotEvents(
        previous: com.tencent.kuiklybase.kline.store.KLineStoreSnapshot?,
        current: com.tencent.kuiklybase.kline.store.KLineStoreSnapshot,
    ) {
        fun emit(name: String, payload: Map<String, Any?>) { post { snapshotCallbacks[name]?.invoke(payload) } }
        if (previous?.viewport != current.viewport) current.viewport?.let { viewport ->
            emit("onVisibleRangeChange", mapOf("startIndex" to kotlin.math.floor(viewport.startIndex).toInt(), "endIndex" to kotlin.math.ceil(viewport.endIndex).toInt()))
        }
        if (previous?.clickSelection != current.clickSelection) current.clickSelection?.let { selected ->
            emit("onBarClick", mapOf("timestamp" to selected.timestamp, "index" to selected.index))
        }
        if (previous?.loadState != current.loadState) emit("onLoadStateChange", mapOf(
            "initial" to current.loadState.initial.name.lowercase(), "before" to current.loadState.before.name.lowercase(), "after" to current.loadState.after.name.lowercase(),
        ))
        if (previous?.selectedOverlayId != current.selectedOverlayId) emit("onOverlayClick", mapOf("id" to current.selectedOverlayId))
        if (previous?.overlayRevision != current.overlayRevision) emit("onOverlayChange", mapOf("revision" to current.overlayRevision))
        if (previous?.period != current.period) current.period?.let { value -> emit("onPeriodChange", mapOf("value" to value.span, "unit" to value.unit.name.lowercase())) }
        if (previous?.indicatorRevision != current.indicatorRevision) emit("onIndicatorChange", mapOf("ids" to current.indicatorInstances.joinToString(",") { it.id }))
    }

    private inline fun runBridgeUpdate(property: String, update: () -> Unit) {
        try {
            update()
        } catch (error: Exception) {
            val message = "Invalid $property JSON: ${error.message ?: error::class.simpleName}"
            if (errorCallback == null) pendingBridgeError = message else emitBridgeError(message)
        }
    }

    private fun emitBridgeError(message: String) {
        errorCallback?.invoke(mapOf("code" to "INVALID_ARGUMENT", "message" to message))
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
            val item = values.getJSONObject(index)
            KLineBar(
                timestamp = item.getLong("timestamp"), open = item.getDouble("open"),
                high = item.getDouble("high"), low = item.getDouble("low"), close = item.getDouble("close"),
                volume = item.optDouble("volume", 0.0), turnover = item.optDouble("turnover", 0.0),
            )
        }
    }

    private fun parseSignals(json: String): List<KLineSignal> {
        val values = JSONArray(json)
        return List(values.length()) { index ->
            val item = values.getJSONObject(index)
            KLineSignal(
                id = item.getString("id"), timestamp = item.getLong("timestamp"), value = item.getDouble("value"),
                type = KLineSignalType.valueOf(item.optString("type", "INFO").uppercase()),
                title = item.getString("title"), summary = item.getString("summary"),
                confidence = if (item.has("confidence")) item.getDouble("confidence") else null,
            )
        }
    }

    private fun parsePane(value: JSONObject): KLinePane {
        val id = value.getString("id")
        val kind = if (value.optString("kind").equals("price", true)) KLinePaneKind.PRICE else KLinePaneKind.INDICATOR
        val axes = value.optJSONArray("yAxes")?.let { array ->
            List(array.length()) { index -> parseYAxis(array.getJSONObject(index), "$id-y-$index") }
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

    private fun parseYAxis(value: JSONObject, fallbackId: String) = KLineYAxis(
        id = value.optString("id", fallbackId),
        minValue = value.optDouble("minValue", 0.0),
        maxValue = value.optDouble("maxValue", 100.0),
        mode = KLineYAxisMode.valueOf(value.optString("mode", "normal").uppercase()),
        referenceValue = if (value.has("referenceValue") && !value.isNull("referenceValue")) value.getDouble("referenceValue") else null,
        autoScale = value.optBoolean("autoScale", true),
    )

    private fun parseIndicator(value: JSONObject): KLineIndicatorInstance {
        val templateName = value.optString("templateName").ifBlank { value.optString("template") }
        val template = KLineBuiltInIndicators.templates.firstOrNull { it.name.equals(templateName, true) }
            ?: error("Unknown indicator template: $templateName")
        val params = value.optJSONArray("params")?.let { array -> List(array.length()) { array.getDouble(it) } }
            ?: template.defaultParams
        return template.instance(
            id = value.getString("id"),
            paneId = value.optString("paneId", "price"),
            params = params,
            precision = value.optInt("precision", 2),
            visible = value.optBoolean("visible", true),
        )
    }

    private fun parseOverlayConfig(value: JSONObject): KLineOverlayConfig {
        val points = value.optJSONArray("points")?.let { array ->
            List(array.length()) { index -> array.getJSONObject(index).let { KLineOverlayPoint(it.getLong("timestamp"), it.getDouble("value")) } }
        }.orEmpty()
        val styles = linkedMapOf<String, KLineOverlayFigureStyle>()
        value.optJSONObject("styles")?.let { json ->
            json.keys().forEach { key ->
                val style = json.getJSONObject(key)
                val dash = style.optJSONArray("lineDash")?.let { array -> List(array.length()) { array.getDouble(it) } }.orEmpty()
                styles[key] = KLineOverlayFigureStyle(
                    color = style.optString("color", "#2F80ED"), lineWidth = style.optDouble("lineWidth", 1.0),
                    lineDash = dash, textSize = style.optDouble("textSize", 12.0),
                )
            }
        }
        val extendData = linkedMapOf<String, String>()
        value.optJSONObject("extendData")?.let { json -> json.keys().forEach { extendData[it] = json.optString(it) } }
        return KLineOverlayConfig(
            templateName = value.getString("templateName"),
            groupId = value.optString("groupId").ifBlank { null },
            paneId = value.optString("paneId", "price"), points = points,
            visible = value.optBoolean("visible", true), locked = value.optBoolean("locked", false),
            magnetMode = magnetMode(value.optString("magnetMode")), zIndex = value.optInt("zIndex", 0),
            styles = styles, extendData = extendData,
        )
    }

    private fun stateToMap(state: KLineChartState): Map<String, Any?> = mapOf(
        "viewport" to state.viewport?.let { mapOf("startIndex" to it.startIndex, "endIndex" to it.endIndex, "barSpace" to it.barSpace, "rightOffset" to it.rightOffset, "zoomAnchor" to it.zoomAnchor) },
        "panes" to state.panes.map(::paneToMap),
        "indicators" to state.indicatorInstances.map(::indicatorToMap),
        "overlays" to state.overlayInstances.map { overlayToMap(it.id, it.templateName, it.groupId, it.paneId, it.points, it.visible, it.locked, it.magnetMode, it.zIndex, it.styles, it.extendData) },
        "theme" to if (state.theme == KLineTheme.DARK) "dark" else "light",
    )

    private fun parseState(value: JSONObject): KLineChartState {
        val viewport = value.optJSONObject("viewport")?.let {
            KLineViewport(it.getDouble("startIndex"), it.getDouble("endIndex"), it.getDouble("barSpace"), it.getDouble("rightOffset"), if (it.has("zoomAnchor") && !it.isNull("zoomAnchor")) it.getDouble("zoomAnchor") else null)
        }
        val panes = value.optJSONArray("panes")?.let { array -> List(array.length()) { parsePane(array.getJSONObject(it)) } }.orEmpty()
        val indicators = value.optJSONArray("indicators")?.let { array -> List(array.length()) { parseIndicator(array.getJSONObject(it)) } }.orEmpty()
        val overlays = value.optJSONArray("overlays")?.let { array -> List(array.length()) { index ->
            val item = array.getJSONObject(index); parseOverlayConfig(item).toInstance(item.getString("id"))
        } }.orEmpty()
        val theme = if (value.optString("theme").equals("dark", true)) KLineTheme.DARK else KLineTheme.LIGHT
        return KLineChartState(viewport, panes, indicators, overlays, theme)
    }

    private fun paneToMap(pane: KLinePane) = mapOf(
        "id" to pane.id, "kind" to pane.kind.name.lowercase(), "order" to pane.order, "weight" to pane.weight,
        "minHeight" to pane.minHeight, "state" to pane.state.name.lowercase(),
        "yAxes" to pane.yAxes.map { mapOf("id" to it.id, "minValue" to it.minValue, "maxValue" to it.maxValue, "mode" to it.mode.name.lowercase(), "referenceValue" to it.referenceValue, "autoScale" to it.autoScale) },
    )

    private fun indicatorToMap(value: KLineIndicatorInstance) = mapOf(
        "id" to value.id, "templateName" to value.templateName, "paneId" to value.paneId,
        "params" to value.params, "precision" to value.precision, "visible" to value.visible,
    )

    private fun overlayToMap(id: String, templateName: String, groupId: String?, paneId: String, points: List<KLineOverlayPoint>, visible: Boolean, locked: Boolean, magnetMode: KLineOverlayMagnetMode, zIndex: Int, styles: Map<String, KLineOverlayFigureStyle>, extendData: Map<String, String>) = mapOf(
        "id" to id, "templateName" to templateName, "groupId" to groupId, "paneId" to paneId,
        "points" to points.map { mapOf("timestamp" to it.timestamp, "value" to it.value) }, "visible" to visible,
        "locked" to locked, "magnetMode" to magnetMode.name.lowercase(), "zIndex" to zIndex,
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

    private fun magnetMode(value: String): KLineOverlayMagnetMode = when (value.lowercase()) {
        "weak" -> KLineOverlayMagnetMode.WEAK
        "strong" -> KLineOverlayMagnetMode.STRONG
        else -> KLineOverlayMagnetMode.NONE
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
                val kind = if (value.optString("kind").equals("price", ignoreCase = true)) {
                    KLinePaneKind.PRICE
                } else {
                    KLinePaneKind.INDICATOR
                }
                controller.setPane(
                    KLinePane(
                        id = id,
                        kind = kind,
                        order = value.optInt("order", index),
                        weight = value.optDouble("weight", if (kind == KLinePaneKind.PRICE) 3.0 else 1.0),
                        minHeight = value.optDouble("minHeight", if (kind == KLinePaneKind.PRICE) 120.0 else 60.0),
                        yAxes = listOf(KLineYAxis("$id-y", 0.0, 100.0, autoScale = true)),
                    ),
                )
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
                val params = if (paramsJson == null) template.defaultParams else {
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
        postInvalidateOnAnimation()
    }
}
