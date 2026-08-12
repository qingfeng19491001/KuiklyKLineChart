package com.tencent.kuiklybase.kline.demo

import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuiklybase.kline.KLinePointerEvent
import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.demo.shared.KLineChartView
import com.tencent.kuiklybase.kline.demo.shared.canvas.AndroidKLineCanvasAdapter
import com.tencent.kuiklybase.kline.demo.shared.demo.RandomBarGenerator
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import org.json.JSONObject

class AndroidKLineChartView(context: Context) : View(context), IKuiklyRenderViewExport {
    private val controller = KLineChartController()
    private var symbol = KLineSymbol("00700", "Demo Stock")
    private var period = KLinePeriod(1, KLinePeriodUnit.DAY)
    private var errorCallback: KuiklyRenderCallback? = null
    private var crosshairCallback: KuiklyRenderCallback? = null
    private var lastCrosshair: com.tencent.kuiklybase.kline.interaction.KLineCrosshair? = null
    private val configuredPaneIds = linkedSetOf("price", "volume")
    private val configuredIndicatorIds = linkedSetOf("ma-price", "ma-price-10", "vol")
    private val density = resources.displayMetrics.density
    private val innerView = KLineChartView(
        dataSource = StaticKLineDataSource(RandomBarGenerator.defaultSymbolBars(count = 600)),
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
        innerView.attach()
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
            controller.setTheme(if (propValue == "dark") KLineTheme.DARK else KLineTheme.LIGHT)
            true
        }
        "config" -> {
            applyConfig(JSONObject(propValue as String))
            true
        }
        "onError" -> {
            errorCallback = propValue as KuiklyRenderCallback
            true
        }
        "onCrosshairChange" -> {
            crosshairCallback = propValue as KuiklyRenderCallback
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
        super.onDetachedFromWindow()
    }

    private fun dispatch(event: KLinePointerEvent) {
        innerView.onPointerEvent(event)
        postInvalidateOnAnimation()
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
