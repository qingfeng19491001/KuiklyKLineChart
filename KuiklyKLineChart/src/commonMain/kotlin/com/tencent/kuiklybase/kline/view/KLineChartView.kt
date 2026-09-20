package com.tencent.kuiklybase.kline.view

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLineDataSource

/**
 * Kuikly 声明式 K 线图组件。
 *
 * 该组件作为 K 线渲染核心与 Kuikly DSL 之间的桥接层，对外暴露统一的属性与事件，
 * 底层由宿主通过平台扩展 View 机制注册名为 `KRKLineChart` 的原生视图承载渲染。
 * 当前仓库提供 Android 注册实现；其他平台可复用公共渲染内核并按 Kuikly 扩展 View
 * 规范实现同名组件。
 *
 * 业务侧使用方式：
 * ```
 * KLineChart {
 *     attr {
 *         symbol(ticker = "00700", name = "腾讯控股")
 *         period(value = 1, unit = "day")
 *         theme("light")
 *     }
 *     event {
 *         onError { code, message -> ... }
 *         onCrosshairChange { timestamp, price -> ... }
 *     }
 * }
 * ```
 *
 * 组件方法（通过 ViewRef 获取实例后调用）：
 * - [scrollToLatest]：滚动到最新一根 K 线
 * - [zoom]：按因子缩放
 * - [resetViewport]：重置视口
 * - [beginOverlay]：开始绘制画线工具
 * - [cancelInteraction]：取消当前交互
 *
 * @see KLineChartAttr
 * @see KLineChartEvent
 */
public class KLineChartView : DeclarativeBaseView<KLineChartAttr, KLineChartEvent>() {

    override fun createAttr(): KLineChartAttr = KLineChartAttr()

    override fun createEvent(): KLineChartEvent = KLineChartEvent()

    override fun viewName(): String = VIEW_NAME

    /* ----------------------- 组件方法（实现在 Native 侧） ----------------------- */

    /**
     * 滚动到最新一根 K 线。
     */
    public fun scrollToLatest() {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(METHOD_SCROLL_TO_LATEST, null)
        }
    }

    /**
     * 按因子缩放视口，factor > 1 放大，factor < 1 缩小。
     */
    public fun zoom(factor: Double) {
        performTaskWhenRenderViewDidLoad {
            val params = JSONObject().apply { put("factor", factor) }
            renderView?.callMethod(METHOD_ZOOM, params.toString())
        }
    }

    /**
     * 重置视口到默认状态。
     */
    public fun resetViewport() {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(METHOD_RESET_VIEWPORT, null)
        }
    }

    public fun scrollToTimestamp(timestamp: Long) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(METHOD_SCROLL_TO_TIMESTAMP, JSONObject().apply { put("timestamp", timestamp) }.toString())
        }
    }

    public fun scrollByBars(count: Double) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(METHOD_SCROLL_BY_BARS, JSONObject().apply { put("count", count) }.toString())
        }
    }

    public fun zoomAtTimestamp(factor: Double, timestamp: Long) {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(
                METHOD_ZOOM_AT_TIMESTAMP,
                JSONObject().apply { put("factor", factor); put("timestamp", timestamp) }.toString(),
            )
        }
    }

    public fun loadBefore() { performTaskWhenRenderViewDidLoad { renderView?.callMethod(METHOD_LOAD_BEFORE, null) } }
    public fun loadAfter() { performTaskWhenRenderViewDidLoad { renderView?.callMethod(METHOD_LOAD_AFTER, null) } }
    public fun retryInitialLoad() { performTaskWhenRenderViewDidLoad { renderView?.callMethod(METHOD_RETRY_INITIAL_LOAD, null) } }

    /** Creates or replaces a pane from the documented JSON pane schema. */
    public fun setPane(json: String) = callJson(METHOD_SET_PANE, json)

    public fun removePane(paneId: String) = callJson(METHOD_REMOVE_PANE, jsonOf("paneId", paneId))

    public fun movePane(paneId: String, index: Int) = callJson(
        METHOD_MOVE_PANE,
        JSONObject().apply { put("paneId", paneId); put("index", index) }.toString(),
    )

    /** state is `normal`, `minimized`, or `maximized`. */
    public fun setPaneState(paneId: String, state: String) = callJson(
        METHOD_SET_PANE_STATE,
        JSONObject().apply { put("paneId", paneId); put("state", state) }.toString(),
    )

    /** Adds an indicator from the documented JSON indicator schema. */
    public fun addIndicator(json: String) = callJson(METHOD_ADD_INDICATOR, json)

    public fun updateIndicator(json: String) = callJson(METHOD_UPDATE_INDICATOR, json)

    public fun removeIndicator(instanceId: String) = callJson(METHOD_REMOVE_INDICATOR, jsonOf("id", instanceId))

    /** Creates an overlay and returns its generated id asynchronously. */
    public fun createOverlay(json: String, callback: (String) -> Unit) = callJson(METHOD_CREATE_OVERLAY, json) { result ->
        callback(bridgeJson(result).optString("id"))
    }

    public fun updateOverlay(instanceId: String, json: String) {
        val value = JSONObject(json).apply { put("id", instanceId) }
        callJson(METHOD_UPDATE_OVERLAY, value.toString())
    }

    public fun removeOverlay(instanceId: String) = callJson(METHOD_REMOVE_OVERLAY, jsonOf("id", instanceId))

    public fun removeOverlayGroup(groupId: String) = callJson(METHOD_REMOVE_OVERLAY_GROUP, jsonOf("groupId", groupId))

    /** Exports a JSON snapshot suitable for [restoreState]. */
    public fun exportState(callback: (String) -> Unit) = callJson(METHOD_EXPORT_STATE, null) { result ->
        callback(bridgeJson(result).toString())
    }

    public fun restoreState(json: String) = callJson(METHOD_RESTORE_STATE, json)

    private fun jsonOf(key: String, value: String): String = JSONObject().apply { put(key, value) }.toString()

    private fun callJson(method: String, params: String?, callback: ((Any?) -> Unit)? = null) {
        performTaskWhenRenderViewDidLoad {
            if (callback == null) renderView?.callMethod(method, params)
            else renderView?.callMethod(method, params, callback)
        }
    }

    /**
     * 开始绘制画线工具。
     *
     * @param templateName 画线模板名，见 [OverlayTemplate]（如 [OverlayTemplate.HORIZONTAL_LINE]）
     * @param paneId 目标窗格 ID，默认为主图 "price"
     * @param magnetMode 磁铁模式："none" / "weak" / "strong"
     */
    public fun beginOverlay(
        templateName: String,
        paneId: String = "price",
        magnetMode: String = "none",
        groupId: String? = null,
        locked: Boolean = false,
    ) {
        performTaskWhenRenderViewDidLoad {
            val params = JSONObject().apply {
                put("templateName", templateName)
                put("paneId", paneId)
                put("magnetMode", magnetMode)
                if (groupId != null) put("groupId", groupId)
                put("locked", locked)
            }
            renderView?.callMethod(METHOD_BEGIN_OVERLAY, params.toString())
        }
    }

    /**
     * 取消当前交互（画线 draft / 十字光标 / 拖拽等）。
     */
    public fun cancelInteraction() {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(METHOD_CANCEL_INTERACTION, null)
        }
    }

    /**
     * 清除十字光标。
     */
    public fun clearCrosshair() {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(METHOD_CLEAR_CROSSHAIR, null)
        }
    }

    /**
     * 删除当前选中的画线图形。
     */
    public fun deleteSelectedOverlay() {
        performTaskWhenRenderViewDidLoad {
            renderView?.callMethod(METHOD_DELETE_SELECTED_OVERLAY, null)
        }
    }

    public companion object {
        /** Native 侧注册的组件名，全端必须一致。 */
        public const val VIEW_NAME: String = "KRKLineChart"

        /* setProp keys — Host 与三端壳只认这里，禁止再解析一份 */
        public const val PROP_BINDING_ID: String = "bindingId"
        public const val PROP_SYMBOL: String = "symbol"
        public const val PROP_PERIOD: String = "period"
        public const val PROP_THEME: String = "theme"
        public const val PROP_MODE: String = "mode"
        public const val PROP_PRICE_STYLE: String = "priceStyle"
        public const val PROP_BARS: String = "bars"
        public const val PROP_SIGNALS: String = "signals"
        public const val PROP_CONFIG: String = "config"
        /** Host-only：壳注入一次物理像素密度，不走 DSL。 */
        public const val PROP_DENSITY: String = "density"

        /* 组件方法名，与 Native 侧 call(method, params, callback) 的 method 字段对齐 */
        public const val METHOD_SCROLL_TO_LATEST: String = "scrollToLatest"
        public const val METHOD_ZOOM: String = "zoom"
        public const val METHOD_RESET_VIEWPORT: String = "resetViewport"
        public const val METHOD_SCROLL_TO_TIMESTAMP: String = "scrollToTimestamp"
        public const val METHOD_SCROLL_BY_BARS: String = "scrollByBars"
        public const val METHOD_ZOOM_AT_TIMESTAMP: String = "zoomAtTimestamp"
        public const val METHOD_LOAD_BEFORE: String = "loadBefore"
        public const val METHOD_LOAD_AFTER: String = "loadAfter"
        public const val METHOD_RETRY_INITIAL_LOAD: String = "retryInitialLoad"
        public const val METHOD_BEGIN_OVERLAY: String = "beginOverlay"
        public const val METHOD_CANCEL_INTERACTION: String = "cancelInteraction"
        public const val METHOD_CLEAR_CROSSHAIR: String = "clearCrosshair"
        public const val METHOD_DELETE_SELECTED_OVERLAY: String = "deleteSelectedOverlay"
        public const val METHOD_SET_PANE: String = "setPane"
        public const val METHOD_REMOVE_PANE: String = "removePane"
        public const val METHOD_MOVE_PANE: String = "movePane"
        public const val METHOD_SET_PANE_STATE: String = "setPaneState"
        public const val METHOD_ADD_INDICATOR: String = "addIndicator"
        public const val METHOD_UPDATE_INDICATOR: String = "updateIndicator"
        public const val METHOD_REMOVE_INDICATOR: String = "removeIndicator"
        public const val METHOD_CREATE_OVERLAY: String = "createOverlay"
        public const val METHOD_UPDATE_OVERLAY: String = "updateOverlay"
        public const val METHOD_REMOVE_OVERLAY: String = "removeOverlay"
        public const val METHOD_REMOVE_OVERLAY_GROUP: String = "removeOverlayGroup"
        public const val METHOD_EXPORT_STATE: String = "exportState"
        public const val METHOD_RESTORE_STATE: String = "restoreState"

        internal val PROP_KEYS: Set<String> = setOf(
            PROP_BINDING_ID, PROP_SYMBOL, PROP_PERIOD, PROP_THEME, PROP_MODE, PROP_PRICE_STYLE,
            PROP_BARS, PROP_SIGNALS, PROP_CONFIG, PROP_DENSITY,
        )

        internal val METHOD_KEYS: Set<String> = setOf(
            METHOD_SCROLL_TO_LATEST, METHOD_ZOOM, METHOD_RESET_VIEWPORT, METHOD_SCROLL_TO_TIMESTAMP,
            METHOD_SCROLL_BY_BARS, METHOD_ZOOM_AT_TIMESTAMP, METHOD_LOAD_BEFORE, METHOD_LOAD_AFTER,
            METHOD_RETRY_INITIAL_LOAD, METHOD_BEGIN_OVERLAY, METHOD_CANCEL_INTERACTION,
            METHOD_CLEAR_CROSSHAIR, METHOD_DELETE_SELECTED_OVERLAY, METHOD_SET_PANE, METHOD_REMOVE_PANE,
            METHOD_MOVE_PANE, METHOD_SET_PANE_STATE, METHOD_ADD_INDICATOR, METHOD_UPDATE_INDICATOR,
            METHOD_REMOVE_INDICATOR, METHOD_CREATE_OVERLAY, METHOD_UPDATE_OVERLAY, METHOD_REMOVE_OVERLAY,
            METHOD_REMOVE_OVERLAY_GROUP, METHOD_EXPORT_STATE, METHOD_RESTORE_STATE,
        )
    }

    /** Public overlay template names accepted by [beginOverlay] / [createOverlay]. */
    public object OverlayTemplate {
        public const val HORIZONTAL_LINE: String = "HORIZONTAL_LINE"
        public const val HORIZONTAL_RAY: String = "HORIZONTAL_RAY"
        public const val HORIZONTAL_SEGMENT: String = "HORIZONTAL_SEGMENT"
        public const val VERTICAL_LINE: String = "VERTICAL_LINE"
        public const val VERTICAL_RAY: String = "VERTICAL_RAY"
        public const val VERTICAL_SEGMENT: String = "VERTICAL_SEGMENT"
        public const val SEGMENT: String = "SEGMENT"
        public const val TREND_LINE: String = "TREND_LINE"
        public const val STRAIGHT_LINE: String = "STRAIGHT_LINE"
        public const val RAY: String = "RAY"
        public const val PRICE_LINE: String = "PRICE_LINE"
        public const val SIMPLE_TAG: String = "SIMPLE_TAG"
        public const val PARALLEL_LINES: String = "PARALLEL_LINES"
        public const val PRICE_CHANNEL: String = "PRICE_CHANNEL"
        public const val FIBONACCI_RETRACEMENT: String = "FIBONACCI_RETRACEMENT"
        public const val TEXT: String = "TEXT"
        public const val FREEHAND: String = "FREEHAND"

        internal val KEYS: Set<String> = setOf(
            HORIZONTAL_LINE, HORIZONTAL_RAY, HORIZONTAL_SEGMENT, VERTICAL_LINE, VERTICAL_RAY, VERTICAL_SEGMENT,
            SEGMENT, TREND_LINE, STRAIGHT_LINE, RAY, PRICE_LINE, SIMPLE_TAG, PARALLEL_LINES, PRICE_CHANNEL,
            FIBONACCI_RETRACEMENT, TEXT, FREEHAND,
        )
    }
}

/**
 * K 线图组件属性。
 *
 * 属性通过 `"key" with value` 语法透传给 Native 侧，
 * Native 侧在 `setProp(propKey, propValue)` 中按 key 接收。
 */
public class KLineChartAttr : Attr() {

    internal fun binding(id: String): KLineChartAttr { KLineChartView.PROP_BINDING_ID with id; return this }

    /**
     * 设置交易标的。
     *
     * @param ticker 标的代码，如 "00700"、"600519"
     * @param name 标的名称，可选
     */
    public fun symbol(ticker: String, name: String = ticker): KLineChartAttr {
        val params = JSONObject().apply {
            put("ticker", ticker)
            put("name", name)
        }
        KLineChartView.PROP_SYMBOL with params.toString()
        return this
    }

    /**
     * 设置周期。
     *
     * @param value 周期数值，如 1
     * @param unit 周期单位："minute" / "hour" / "day" / "week" / "month"
     */
    public fun period(value: Int, unit: String): KLineChartAttr {
        val params = JSONObject().apply {
            put("value", value)
            put("unit", unit)
        }
        KLineChartView.PROP_PERIOD with params.toString()
        return this
    }

    /**
     * 设置主题。
     *
     * @param name "light" 或 "dark"
     */
    public fun theme(name: String): KLineChartAttr {
        KLineChartView.PROP_THEME with name
        return this
    }

    /** Selects the shared engine preset: `full` or `compact`. */
    public fun mode(name: String): KLineChartAttr {
        KLineChartView.PROP_MODE with name
        return this
    }

    /** Selects price rendering: `candle`, `candle_hollow`, `candle_up_stroke`, `candle_down_stroke`, `ohlc`, `line`, or `area`. */
    public fun priceStyle(name: String): KLineChartAttr {
        KLineChartView.PROP_PRICE_STYLE with name
        return this
    }

    /** Replaces the static/pushed candle snapshot encoded as a JSON array. */
    public fun bars(json: String): KLineChartAttr {
        KLineChartView.PROP_BARS with json
        return this
    }

    /** Replaces semantic AI signals encoded as a JSON array. */
    public fun signals(json: String): KLineChartAttr {
        KLineChartView.PROP_SIGNALS with json
        return this
    }

    /**
     * 设置窗格与指标配置（JSON 字符串）。
     *
     * JSON 结构示例：
     * ```
     * {
     *   "panes": [
     *     {"id":"price","kind":"price","weight":3.0,"minHeight":120},
     *     {"id":"volume","kind":"indicator","weight":1.0,"minHeight":60}
     *   ],
     *   "indicators": [
     *     {"id":"ma5","template":"MA","paneId":"price","params":[5.0]},
     *     {"id":"vol","template":"VOLUME","paneId":"volume","params":[]}
     *   ]
     * }
     * ```
     */
    public fun config(json: String): KLineChartAttr {
        KLineChartView.PROP_CONFIG with json
        return this
    }
}

/**
 * K 线图组件事件。
 */
public class KLineChartEvent : Event() {

    public fun onVisibleRangeChange(handler: (Int, Int) -> Unit) = registerJson(EVENT_VISIBLE_RANGE_CHANGE) { handler(it.optInt("startIndex"), it.optInt("endIndex")) }
    public fun onBarClick(handler: (Long, Int) -> Unit) = registerJson(EVENT_BAR_CLICK) { handler(it.optLong("timestamp"), it.optInt("index")) }
    public fun onLoadStateChange(handler: (String, String, String) -> Unit) = registerJson(EVENT_LOAD_STATE_CHANGE) { handler(it.optString("initial"), it.optString("before"), it.optString("after")) }
    public fun onOverlayClick(handler: (String?) -> Unit) = registerJson(EVENT_OVERLAY_CLICK) { handler(it.optString("id").ifBlank { null }) }
    public fun onOverlayChange(handler: (Long) -> Unit) = registerJson(EVENT_OVERLAY_CHANGE) { handler(it.optLong("revision")) }
    public fun onPeriodChange(handler: (Int, String) -> Unit) = registerJson(EVENT_PERIOD_CHANGE) { handler(it.optInt("value"), it.optString("unit")) }
    public fun onIndicatorChange(handler: (List<String>) -> Unit) = registerJson(EVENT_INDICATOR_CHANGE) { handler(it.optString("ids").split(',').filter(String::isNotBlank)) }
    public fun onPaneLayoutChange(handler: (Float, Float, Float) -> Unit) {
        register(EVENT_PANE_LAYOUT_CHANGE) { params ->
            fun number(name: String): Float = when (params) {
                is JSONObject -> params.optDouble(name).toFloat()
                is Map<*, *> -> (params[name] as? Number)?.toFloat() ?: 0f
                else -> 0f
            }
            handler(number("priceTop"), number("firstTop"), number("secondTop"))
        }
    }

    public fun onPaneHeaderClick(handler: (String) -> Unit) {
        register(EVENT_PANE_HEADER_CLICK) { params ->
            handler(
                when (params) {
                    is JSONObject -> params.optString("paneId")
                    is Map<*, *> -> params["paneId"] as? String ?: ""
                    else -> ""
                },
            )
        }
    }

    private fun registerJson(name: String, handler: (JSONObject) -> Unit) {
        register(name) { params -> handler(bridgeJson(params)) }
    }

    public fun onSignalClick(handler: (id: String, title: String, summary: String) -> Unit) {
        register(EVENT_SIGNAL_CLICK) { params ->
            val json = bridgeJson(params)
            handler(json.optString("id"), json.optString("title"), json.optString("summary"))
        }
    }

    /**
     * 注册错误回调。当数据加载失败、渲染异常等场景触发。
     */
    public fun onError(handler: (code: String, message: String) -> Unit) {
        register(EVENT_ERROR) { params ->
            val json = bridgeJson(params)
            handler(json.optString("code"), json.optString("message"))
        }
    }

    /**
     * 注册十字光标变化回调。
     *
     * @param handler 接收 timestamp（可能为 null）和 price（可能为 null）
     */
    public fun onCrosshairChange(handler: (timestamp: Long?, price: Double?) -> Unit) {
        register(EVENT_CROSSHAIR_CHANGE) { params ->
            val json = bridgeJson(params)
            val ts = if (json.has("timestamp")) json.optLong("timestamp") else null
            val price = if (json.has("price")) json.optDouble("price") else null
            handler(ts, price)
        }
    }

    public companion object {
        public const val EVENT_ERROR: String = "onError"
        public const val EVENT_CROSSHAIR_CHANGE: String = "onCrosshairChange"
        public const val EVENT_SIGNAL_CLICK: String = "onSignalClick"
        public const val EVENT_VISIBLE_RANGE_CHANGE: String = "onVisibleRangeChange"
        public const val EVENT_BAR_CLICK: String = "onBarClick"
        public const val EVENT_LOAD_STATE_CHANGE: String = "onLoadStateChange"
        public const val EVENT_OVERLAY_CLICK: String = "onOverlayClick"
        public const val EVENT_OVERLAY_CHANGE: String = "onOverlayChange"
        public const val EVENT_PERIOD_CHANGE: String = "onPeriodChange"
        public const val EVENT_INDICATOR_CHANGE: String = "onIndicatorChange"
        public const val EVENT_PANE_LAYOUT_CHANGE: String = "onPaneLayoutChange"
        public const val EVENT_PANE_HEADER_CLICK: String = "onPaneHeaderClick"

        internal val EVENT_KEYS: Set<String> = setOf(
            EVENT_ERROR, EVENT_CROSSHAIR_CHANGE, EVENT_SIGNAL_CLICK, EVENT_VISIBLE_RANGE_CHANGE,
            EVENT_BAR_CLICK, EVENT_LOAD_STATE_CHANGE, EVENT_OVERLAY_CLICK, EVENT_OVERLAY_CHANGE,
            EVENT_PERIOD_CHANGE, EVENT_INDICATOR_CHANGE, EVENT_PANE_LAYOUT_CHANGE, EVENT_PANE_HEADER_CLICK,
        )
    }
}

/**
 * K 线图声明式扩展函数。
 *
 * 在 Kuikly DSL 中通过 `KLineChart { attr { ... } event { ... } }` 使用。
 */
public fun ViewContainer<*, *>.KLineChart(init: KLineChartView.() -> Unit) {
    addChild(KLineChartView(), init)
}

internal fun bridgeJson(value: Any?): JSONObject = when (value) {
    is JSONObject -> value
    is Map<*, *> -> JSONObject(bridgeJsonString(value))
    else -> JSONObject()
}

private fun bridgeJsonString(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries
        .filter { it.key is String }
        .joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${bridgeJsonString(key as String)}:${bridgeJsonString(item)}"
        }
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { bridgeJsonString(it) }
    else -> bridgeJsonString(value.toString())
}

public fun ViewContainer<*, *>.KLineChart(
    dataSource: KLineDataSource,
    controller: KLineChartController = KLineChartController(),
    init: KLineChartView.() -> Unit = {},
) {
    val bindingId = KLineChartBindingRegistry.register(dataSource, controller)
    addChild(KLineChartView()) {
        attr { binding(bindingId) }
        init()
    }
}
