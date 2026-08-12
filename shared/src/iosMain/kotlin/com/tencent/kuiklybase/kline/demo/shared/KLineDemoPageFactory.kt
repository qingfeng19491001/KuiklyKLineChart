package com.tencent.kuiklybase.kline.demo.shared

import com.tencent.kuiklybase.kline.KLinePointerEvent
import com.tencent.kuiklybase.kline.demo.shared.canvas.KLineCanvasAdapter
import com.tencent.kuiklybase.kline.demo.shared.demo.BasicKLinePage
import kotlin.native.ObjCName
import kotlin.experimental.ExperimentalObjCName

@OptIn(ExperimentalObjCName::class)
@ObjCName("KLineDemoPageFactory")
object KLineDemoPageFactory {
    fun createBasicPage(): BasicKLinePageHandle = BasicKLinePageHandle(BasicKLinePage())
}

@OptIn(ExperimentalObjCName::class)
@ObjCName("BasicKLinePageHandle")
class BasicKLinePageHandle internal constructor(private val page: BasicKLinePage) {
    init { page.start() }

    fun setSize(width: Int, height: Int) = page.onSize(width, height)

    fun draw(adapter: KLineCanvasAdapter) = page.draw(adapter)

    fun onDown(x: Double, y: Double) = page.view.onPointerEvent(KLinePointerEvent.Down(x, y))
    fun onMove(x: Double, y: Double, scaleFactor: Double = 1.0, pointerCount: Int = 1) =
        page.view.onPointerEvent(KLinePointerEvent.Move(x, y, scaleFactor, pointerCount))
    fun onUp(x: Double, y: Double) = page.view.onPointerEvent(KLinePointerEvent.Up(x, y))
    fun onCancel(x: Double, y: Double) = page.view.onPointerEvent(KLinePointerEvent.Cancel(x, y))
    fun onLongPress(x: Double, y: Double) = page.view.onPointerEvent(KLinePointerEvent.LongPress(x, y))
    fun onTap(x: Double, y: Double) = page.view.onPointerEvent(KLinePointerEvent.Tap(x, y))

    fun dispose() = page.dispose()
}
