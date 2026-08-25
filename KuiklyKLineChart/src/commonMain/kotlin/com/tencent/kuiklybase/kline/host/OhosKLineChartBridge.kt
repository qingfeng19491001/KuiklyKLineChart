package com.tencent.kuiklybase.kline.host

/**
 * OHOS NAPI-facing wrapper around [KLinePlatformHost].
 */
public class OhosKLineChartBridge {
    private val host = KLinePlatformHost(queueEvents = true)

    public fun setProp(key: String, value: String): Boolean = host.setProp(key, value)

    public fun call(method: String, params: String): String = host.call(method, params)

    public fun resize(width: Int, height: Int) = host.resize(width, height)

    public fun pointer(kind: String, x: Double, y: Double, scale: Double, count: Int) =
        host.pointer(kind, x, y, scale, count)

    public fun renderCommands(): String = host.renderCommands()

    public fun pollEvents(): String = host.pollEvents()

    public fun dispose() = host.dispose()
}
