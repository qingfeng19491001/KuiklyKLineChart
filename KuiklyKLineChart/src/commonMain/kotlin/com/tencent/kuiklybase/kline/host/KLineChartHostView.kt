package com.tencent.kuiklybase.kline.host

import com.tencent.kuiklybase.kline.KLineChartEngine
import com.tencent.kuiklybase.kline.KLinePointerEvent
import com.tencent.kuiklybase.kline.KLinePointerDispatchOutcome
import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLineDataSource
import com.tencent.kuiklybase.kline.host.canvas.KLineCanvasAdapter
import com.tencent.kuiklybase.kline.host.canvas.KLineCanvasRenderer
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.store.KLineStoreSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class KLineChartDsl {
    internal var theme: KLineTheme = KLineTheme.LIGHT
    internal var panes: MutableList<KLinePane> = mutableListOf(
        KLinePane(
            id = "price", kind = KLinePaneKind.PRICE, order = 0, weight = 3.0, minHeight = 120.0,
            yAxes = listOf(KLineYAxis("price-y", 0.0, 100.0, autoScale = true)),
        ),
        KLinePane(
            id = "volume", kind = KLinePaneKind.INDICATOR, order = 1, weight = 1.0, minHeight = 60.0,
            yAxes = listOf(KLineYAxis("volume-y", 0.0, 100.0, autoScale = true)),
        ),
    )
    val indicators: MutableList<KLineIndicatorInstance> = mutableListOf()
    internal var onError: (KLineError) -> Unit = {}

    fun theme(block: () -> KLineTheme) { theme = block() }

    fun pane(pane: KLinePane) {
        val existing = panes.indexOfFirst { it.id == pane.id }
        if (existing >= 0) panes[existing] = pane else panes.add(pane)
        panes.sortBy { it.order }
    }

    fun indicator(instance: KLineIndicatorInstance) { indicators += instance }

    fun onError(block: (KLineError) -> Unit) { onError = block }

    fun addDefaultIndicators() {
        indicators += KLineBuiltInIndicators.MA.instance("ma-price", "price", listOf(5.0))
        indicators += KLineBuiltInIndicators.MA.instance("ma-price-10", "price", listOf(10.0))
        indicators += KLineBuiltInIndicators.VOLUME.instance("vol", "volume", emptyList())
    }
}

class KLineChartHostView(
    private val dataSource: KLineDataSource,
    private val controller: KLineChartController = KLineChartController(),
    private val onInvalidate: () -> Unit = {},
    private val onSnapshot: (KLineStoreSnapshot) -> Unit = {},
    dsl: KLineChartDsl.() -> Unit = {},
) {
    private val dslConfig = KLineChartDsl().apply(dsl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine: KLineChartEngine = KLineChartEngine(
        dataSource = dataSource,
        controller = controller,
        onError = dslConfig.onError,
    )
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        dslConfig.panes.forEach { pane -> controller.setPane(pane) }
        dslConfig.indicators.forEach { instance -> controller.addIndicator(instance) }
        controller.setTheme(dslConfig.theme)
        engine.snapshotFlow
            .onEach { snapshot ->
                onSnapshot(snapshot)
                onInvalidate()
            }
            .launchIn(scope)
    }

    fun onSizeChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        currentWidth = width
        currentHeight = height
        engine.updateBounds(KLineRect(0.0, 0.0, width.toDouble(), height.toDouble()))
    }

    fun draw(canvas: KLineCanvasAdapter) {
        if (currentWidth <= 0 || currentHeight <= 0) return
        val bounds = KLineRect(0.0, 0.0, currentWidth.toDouble(), currentHeight.toDouble())
        val plan = engine.latestRenderPlan(bounds)
        KLineCanvasRenderer.render(plan, canvas)
    }

    fun latestRenderPlan(): com.tencent.kuiklybase.kline.render.KLineRenderPlan? {
        if (currentWidth <= 0 || currentHeight <= 0) return null
        return engine.latestRenderPlan(KLineRect(0.0, 0.0, currentWidth.toDouble(), currentHeight.toDouble()))
    }

    fun onPointerEvent(event: KLinePointerEvent): KLinePointerDispatchOutcome = engine.dispatchPointerEvent(event)

    fun dispose() {
        scope.cancel()
        engine.dispose()
    }
}
