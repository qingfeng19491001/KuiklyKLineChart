package com.kuikly.kuiklyklinechart.shared

import com.tencent.kuiklybase.kline.host.OhosKLineChartBridge as OhosKLineChartBridgeImpl

/**
 * OHOS C ABI facade. Kotlin/Native only exports public types from this module,
 * so NAPI (`napi_init.cpp`) must call this wrapper rather than the library class.
 */
class OhosKLineChartBridge {
    private val impl = OhosKLineChartBridgeImpl()

    fun setProp(key: String, value: String): Boolean = impl.setProp(key, value)

    fun call(method: String, params: String): String = impl.call(method, params)

    fun resize(width: Int, height: Int) = impl.resize(width, height)

    fun pointer(kind: String, x: Double, y: Double, scale: Double, count: Int) =
        impl.pointer(kind, x, y, scale, count)

    fun renderCommands(): String = impl.renderCommands()

    fun pollEvents(): String = impl.pollEvents()

    fun onDetached() = impl.onDetached()

    fun attachIfNeeded(width: Int, height: Int) = impl.attachIfNeeded(width, height)

    fun dispose() = impl.dispose()
}
