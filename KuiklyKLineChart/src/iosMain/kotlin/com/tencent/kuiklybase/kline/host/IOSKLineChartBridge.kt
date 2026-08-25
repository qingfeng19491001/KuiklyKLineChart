package com.tencent.kuiklybase.kline.host

import com.tencent.kuiklybase.kline.KLinePointerEvent
import com.tencent.kuiklybase.kline.host.canvas.KLineCanvasAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * iOS Kuikly expand-View Kotlin mediator over [KLinePlatformHost].
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("KLCChartBridge", exact = true)
class IOSKLineChartBridge(
    private val onInvalidate: () -> Unit,
    private val onEvent: (event: String, params: Map<String, Any?>) -> Unit,
) {
    private val mainScope = MainScope()

    private val host = KLinePlatformHost(
        onInvalidate = { postInvalidate() },
        onEvent = { name, payload -> postEvent(name, payload) },
        queueEvents = false,
    )

    var displayScale: Double
        get() = host.density
        set(value) {
            host.density = value.takeIf { it > 0.0 } ?: 1.0
        }

    fun setProp(propKey: String, propValue: String): Boolean = host.setProp(propKey, propValue)

    fun onEventCallbackBound(event: String) {
        host.notifyEventListenerBound(event)
    }

    fun callMethod(method: String, params: String?): Map<String, Any?>? {
        val result = host.callAsMap(method, params) ?: return null
        return result.takeIf { it.isNotEmpty() }
    }

    fun onSizeChanged(width: Int, height: Int) = host.resize(width, height)

    fun draw(canvas: KLineCanvasAdapter) = host.draw(canvas)

    fun viewDidDisappear() {
        host.onDetached()
    }

    fun viewWillAppear(width: Int, height: Int) {
        host.attachIfNeeded(width, height)
    }

    fun pointerDown(x: Double, y: Double) = host.dispatchPointer(KLinePointerEvent.Down(x, y))

    fun pointerSecondaryDown(x: Double, y: Double) =
        host.dispatchPointer(KLinePointerEvent.SecondaryDown(x, y))

    fun pointerMove(x: Double, y: Double, scaleFactor: Double, pointerCount: Int) =
        host.dispatchPointer(KLinePointerEvent.Move(x, y, scaleFactor, pointerCount))

    fun pointerUp(x: Double, y: Double) = host.dispatchPointer(KLinePointerEvent.Up(x, y))

    fun pointerCancel(x: Double, y: Double) = host.dispatchPointer(KLinePointerEvent.Cancel(x, y))

    fun pointerTap(x: Double, y: Double) = host.dispatchPointer(KLinePointerEvent.Tap(x, y))

    fun pointerLongPress(x: Double, y: Double) =
        host.dispatchPointer(KLinePointerEvent.LongPress(x, y))

    fun resetViewport() {
        host.callAsMap("resetViewport", null)
    }

    fun paneHeaderAt(x: Double, y: Double): String? = host.paneHeaderAt(x, y)

    private fun postInvalidate() {
        mainScope.launch(Dispatchers.Main) { onInvalidate() }
    }

    private fun postEvent(event: String, params: Map<String, Any?>) {
        mainScope.launch(Dispatchers.Main) { onEvent(event, params) }
    }
}
