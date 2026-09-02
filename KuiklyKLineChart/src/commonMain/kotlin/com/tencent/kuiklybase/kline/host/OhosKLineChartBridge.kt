package com.tencent.kuiklybase.kline.host

/**
 * OHOS NAPI-facing wrapper around [KLinePlatformHost].
 */
public class OhosKLineChartBridge {
    private val host = KLinePlatformHost(queueEvents = true)

    public fun setProp(key: String, value: String): Boolean = host.setProp(key, value)

    public fun call(method: String, params: String): String {
        return when (method) {
            CLAIM_POINTER_MOVE -> {
                val parts = params.split(',')
                if (parts.size != 3) {
                    KLineGestureClaim.PENDING.name
                } else {
                    host.claimPointerMove(parts[0].toDouble(), parts[1].toDouble(), parts[2].toInt()).name
                }
            }
            RESET_GESTURE_CLAIM -> {
                host.resetGestureClaim()
                ""
            }
            else -> host.call(method, params)
        }
    }

    public fun resize(width: Int, height: Int) = host.resize(width, height)

    public fun pointer(kind: String, x: Double, y: Double, scale: Double, count: Int) =
        host.pointer(kind, x, y, scale, count)

    public fun renderCommands(): String = host.renderCommands()

    public fun pollEvents(): String = host.pollEvents()

    public fun onDetached() {
        host.onDetached()
    }

    public fun attachIfNeeded(width: Int, height: Int) {
        host.attachIfNeeded(width, height)
    }

    public fun dispose() = host.dispose()

    private companion object {
        const val CLAIM_POINTER_MOVE = "claimPointerMove"
        const val RESET_GESTURE_CLAIM = "resetGestureClaim"
    }
}
