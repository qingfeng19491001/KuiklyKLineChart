package com.tencent.kuiklybase.kline.demo

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
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.demo.shared.KLineChartView
import com.tencent.kuiklybase.kline.demo.shared.canvas.AndroidKLineCanvasAdapter
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.signal.KLineSignal
import com.tencent.kuiklybase.kline.signal.KLineSignalType
import org.json.JSONArray
import org.json.JSONObject

class AndroidKLineChartView(context: Context) : View(context), IKuiklyRenderViewExport {
    private var controller = KLineChartController()
    private var symbol = KLineSymbol("00700", "Demo Stock")
    private var period = KLinePeriod(1, KLinePeriodUnit.DAY)
    private var errorCallback: KuiklyRenderCallback? = null
    private var crosshairCallback: KuiklyRenderCallback? = null
    private var signalClickCallback: KuiklyRenderCallback? = null
    private var lastCrosshair: com.tencent.kuiklybase.kline.interaction.KLineCrosshair? = null
    private val configuredPaneIds = linkedSetOf("price", "volume")
    private val configuredIndicatorIds = linkedSetOf("ma-price", "ma-price-10", "vol")
    private val density = resources.displayMetrics.density
    private var configuredMode = KLineChartMode.FULL
    private var configuredTheme = KLineTheme.LIGHT
    private var configuredSignals: List<KLineSignal> = emptyList()
    private var configuredConfig: String? = null
    private var pendingBridgeError: String? = null
    private var innerView = createInnerView(emptyList())

    private fun createInnerView(bars: List<KLineBar>) = KLineChartView(
        dataSource = StaticKLineDataSource(bars),
        controller = controller,
        onInvalidate = { postInvalidateOnAnimation() },
        onSnapshot = { snapshot ->
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
        view.engine.setSignals(configuredSignals)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            dispatch(KLinePointerEvent.Down(e.x.toDouble(), e.y.toDouble()))
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            dispatch(KLinePointerEvent.Tap(e.x.toDouble(), e.y.toDouble()))
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            dispatch(KLinePointerEvent.Move(e2.x.toDouble(), e2.y.toDouble(), pointerCount = e2.pointerCount))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            dispatch(KLinePointerEvent.LongPress(e.x.toDouble(), e.y.toDouble()))
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            dispatch(
                KLinePointerEvent.Move(
                    detector.focusX.toDouble(),
                    detector.focusY.toDouble(),
                    detector.scaleFactor.toDouble(),
                    2,
                ),
            )
            return true
        }
    })

    init {
        controller.setMarket(symbol, period)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
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
        else -> super.setProp(propKey, propValue)
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            "scrollToLatest" -> controller.scrollToLatest()
            "zoom" -> controller.zoom(JSONObject(params.orEmpty()).optDouble("factor", 1.0))
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> dispatch(KLinePointerEvent.Up(event.x.toDouble(), event.y.toDouble()))
            MotionEvent.ACTION_CANCEL -> dispatch(KLinePointerEvent.Cancel(event.x.toDouble(), event.y.toDouble()))
        }
        return true
    }

    override fun onDetachedFromWindow() {
        innerView.dispose()
        errorCallback = null
        crosshairCallback = null
        signalClickCallback = null
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

    private fun replaceBars(bars: List<KLineBar>) {
        innerView.dispose()
        controller = KLineChartController()
        resetConfiguredComponents()
        innerView = createInnerView(bars)
        if (width > 0 && height > 0) innerView.onSizeChanged(width, height)
        controller.setMarket(symbol, period)
        controller.setTheme(configuredTheme)
        configuredConfig?.let { applyConfig(JSONObject(it)) }
        postInvalidateOnAnimation()
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
                        yAxes = listOf(KLineYAxis("$id-y", 0.0, 100.0)),
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
