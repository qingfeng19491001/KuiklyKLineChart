package com.tencent.kuiklybase.kline.view

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

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

    /**
     * 开始绘制画线工具。
     *
     * @param templateName 画线模板名（如 "TREND_LINE"、"HORIZONTAL_LINE"、"VERTICAL_LINE"、"TEXT"）
     * @param paneId 目标窗格 ID，默认为主图 "price"
     * @param magnetMode 磁铁模式："none" / "weak" / "strong"
     */
    public fun beginOverlay(
        templateName: String,
        paneId: String = "price",
        magnetMode: String = "none",
    ) {
        performTaskWhenRenderViewDidLoad {
            val params = JSONObject().apply {
                put("templateName", templateName)
                put("paneId", paneId)
                put("magnetMode", magnetMode)
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

        /* 组件方法名，与 Native 侧 call(method, params, callback) 的 method 字段对齐 */
        public const val METHOD_SCROLL_TO_LATEST: String = "scrollToLatest"
        public const val METHOD_ZOOM: String = "zoom"
        public const val METHOD_RESET_VIEWPORT: String = "resetViewport"
        public const val METHOD_BEGIN_OVERLAY: String = "beginOverlay"
        public const val METHOD_CANCEL_INTERACTION: String = "cancelInteraction"
        public const val METHOD_CLEAR_CROSSHAIR: String = "clearCrosshair"
        public const val METHOD_DELETE_SELECTED_OVERLAY: String = "deleteSelectedOverlay"
    }
}

/**
 * K 线图组件属性。
 *
 * 属性通过 `"key" with value` 语法透传给 Native 侧，
 * Native 侧在 `setProp(propKey, propValue)` 中按 key 接收。
 */
public class KLineChartAttr : Attr() {

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
        "symbol" with params.toString()
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
        "period" with params.toString()
        return this
    }

    /**
     * 设置主题。
     *
     * @param name "light" 或 "dark"
     */
    public fun theme(name: String): KLineChartAttr {
        "theme" with name
        return this
    }

    /** Selects the shared engine preset: `full` or `compact`. */
    public fun mode(name: String): KLineChartAttr {
        "mode" with name
        return this
    }

    /** Replaces the static/pushed candle snapshot encoded as a JSON array. */
    public fun bars(json: String): KLineChartAttr {
        "bars" with json
        return this
    }

    /** Replaces semantic AI signals encoded as a JSON array. */
    public fun signals(json: String): KLineChartAttr {
        "signals" with json
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
        "config" with json
        return this
    }
}

/**
 * K 线图组件事件。
 */
public class KLineChartEvent : Event() {

    public fun onSignalClick(handler: (id: String, title: String, summary: String) -> Unit) {
        register(EVENT_SIGNAL_CLICK) { params ->
            val json = params as? JSONObject ?: JSONObject()
            handler(json.optString("id"), json.optString("title"), json.optString("summary"))
        }
    }

    /**
     * 注册错误回调。当数据加载失败、渲染异常等场景触发。
     */
    public fun onError(handler: (code: String, message: String) -> Unit) {
        register(EVENT_ERROR) { params ->
            val json = params as? JSONObject ?: JSONObject()
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
            val json = params as? JSONObject ?: JSONObject()
            val ts = if (json.has("timestamp")) json.optLong("timestamp") else null
            val price = if (json.has("price")) json.optDouble("price") else null
            handler(ts, price)
        }
    }

    public companion object {
        public const val EVENT_ERROR: String = "onError"
        public const val EVENT_CROSSHAIR_CHANGE: String = "onCrosshairChange"
        public const val EVENT_SIGNAL_CLICK: String = "onSignalClick"
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
