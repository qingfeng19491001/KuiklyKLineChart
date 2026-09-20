package com.tencent.kuiklybase.kline.host

import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.tencent.kuiklybase.kline.KLinePointerEvent
import com.tencent.kuiklybase.kline.host.canvas.AndroidKLineCanvasAdapter
import com.tencent.kuiklybase.kline.view.KLineChartEvent
import com.tencent.kuiklybase.kline.view.KLineChartView
import kotlin.math.abs

/**
 * Android Kuikly expand-View: gestures and canvas, backed by [KLinePlatformHost].
 */
class AndroidKLineChartView(context: Context) : View(context), IKuiklyRenderViewExport {
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var pressedPaneHeaderId: String? = null
    private var paneHeaderGestureBecamePan = false
    private var paneHeaderDownX = 0f
    private var paneHeaderDownY = 0f
    private val eventCallbacks = mutableMapOf<String, KuiklyRenderCallback>()

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        elevation = 0f
        outlineProvider = null
        clipToOutline = false
    }

    private val platform = KLinePlatformHost(
        onInvalidate = { postInvalidateOnAnimation() },
        onEvent = { name, payload ->
            post { eventCallbacks[name]?.invoke(payload) }
        },
    ).also {
        it.density = resources.displayMetrics.density.toDouble()
    }

    private val density: Float get() = platform.density.toFloat()

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            platform.dispatchPointer(KLinePointerEvent.Down(e.x.toDouble(), e.y.toDouble()))
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            platform.callAsMap(KLineChartView.METHOD_RESET_VIEWPORT, null)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            platform.dispatchPointer(KLinePointerEvent.Tap(e.x.toDouble(), e.y.toDouble()))
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return true
            platform.dispatchPointer(
                KLinePointerEvent.Move(e2.x.toDouble(), e2.y.toDouble(), pointerCount = e2.pointerCount),
            )
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            platform.dispatchPointer(KLinePointerEvent.LongPress(e.x.toDouble(), e.y.toDouble()))
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var accumulatedFactor = 1.0

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            accumulatedFactor = 1.0
            platform.dispatchPointer(KLinePointerEvent.Cancel(detector.focusX.toDouble(), detector.focusY.toDouble()))
            platform.dispatchPointer(
                KLinePointerEvent.SecondaryDown(detector.focusX.toDouble(), detector.focusY.toDouble()),
            )
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            accumulatedFactor *= detector.scaleFactor.toDouble()
            platform.dispatchPointer(
                KLinePointerEvent.Move(
                    detector.focusX.toDouble(),
                    detector.focusY.toDouble(),
                    accumulatedFactor,
                    2,
                ),
            )
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            platform.dispatchPointer(KLinePointerEvent.Up(detector.focusX.toDouble(), detector.focusY.toDouble()))
        }
    })

    override fun setProp(propKey: String, propValue: Any): Boolean {
        when (propKey) {
            KLineChartEvent.EVENT_ERROR,
            KLineChartEvent.EVENT_CROSSHAIR_CHANGE,
            KLineChartEvent.EVENT_SIGNAL_CLICK,
            KLineChartEvent.EVENT_PANE_LAYOUT_CHANGE,
            KLineChartEvent.EVENT_PANE_HEADER_CLICK,
            KLineChartEvent.EVENT_VISIBLE_RANGE_CHANGE,
            KLineChartEvent.EVENT_BAR_CLICK,
            KLineChartEvent.EVENT_LOAD_STATE_CHANGE,
            KLineChartEvent.EVENT_OVERLAY_CLICK,
            KLineChartEvent.EVENT_OVERLAY_CHANGE,
            KLineChartEvent.EVENT_PERIOD_CHANGE,
            KLineChartEvent.EVENT_INDICATOR_CHANGE,
            -> {
                @Suppress("UNCHECKED_CAST")
                eventCallbacks[propKey] = propValue as KuiklyRenderCallback
                platform.notifyEventListenerBound(propKey)
                return true
            }
        }
        return if (propValue is String) {
            platform.setProp(propKey, propValue) || super.setProp(propKey, propValue)
        } else {
            super.setProp(propKey, propValue)
        }
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        val result = platform.callAsMap(method, params)
            ?: return super.call(method, params, callback)
        if (result.isNotEmpty()) callback?.invoke(result)
        return null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        platform.resize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        platform.draw(AndroidKLineCanvasAdapter(canvas, density))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (paneHeaderGestureBecamePan) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE ->
                    platform.dispatchPointer(KLinePointerEvent.Move(event.x.toDouble(), event.y.toDouble()))
                MotionEvent.ACTION_UP -> {
                    platform.dispatchPointer(KLinePointerEvent.Up(event.x.toDouble(), event.y.toDouble()))
                    paneHeaderGestureBecamePan = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    platform.dispatchPointer(KLinePointerEvent.Cancel(event.x.toDouble(), event.y.toDouble()))
                    paneHeaderGestureBecamePan = false
                }
            }
            return true
        }
        val headerId = platform.paneHeaderAt(event.x.toDouble(), event.y.toDouble())
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            headerId != null &&
            eventCallbacks[KLineChartEvent.EVENT_PANE_HEADER_CLICK] != null
        ) {
            pressedPaneHeaderId = headerId
            paneHeaderDownX = event.x
            paneHeaderDownY = event.y
            return true
        }
        pressedPaneHeaderId?.let { pressedId ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (
                    abs(event.x - paneHeaderDownX) > (KLINE_TOUCH_SLOP_VP * density).toFloat() ||
                    abs(event.y - paneHeaderDownY) > (KLINE_TOUCH_SLOP_VP * density).toFloat()
                ) {
                    pressedPaneHeaderId = null
                    paneHeaderGestureBecamePan = true
                    platform.dispatchPointer(
                        KLinePointerEvent.Down(paneHeaderDownX.toDouble(), paneHeaderDownY.toDouble()),
                    )
                    platform.dispatchPointer(KLinePointerEvent.Move(event.x.toDouble(), event.y.toDouble()))
                }
                MotionEvent.ACTION_UP -> {
                    if (headerId == pressedId) {
                        eventCallbacks[KLineChartEvent.EVENT_PANE_HEADER_CLICK]?.invoke(mapOf("paneId" to pressedId))
                    }
                    pressedPaneHeaderId = null
                }
                MotionEvent.ACTION_CANCEL -> pressedPaneHeaderId = null
            }
            return true
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                platform.resetGestureClaim()
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerCount = if (scaleDetector.isInProgress) 2 else event.pointerCount
                when (
                    platform.claimPointerMove(
                        (event.x - touchDownX).toDouble(),
                        (event.y - touchDownY).toDouble(),
                        pointerCount,
                    )
                ) {
                    KLineGestureClaim.CHART -> parent?.requestDisallowInterceptTouchEvent(true)
                    KLineGestureClaim.PARENT -> {
                        parent?.requestDisallowInterceptTouchEvent(false)
                        platform.dispatchPointer(
                            KLinePointerEvent.Cancel(event.x.toDouble(), event.y.toDouble()),
                        )
                    }
                    KLineGestureClaim.PENDING -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                platform.dispatchPointer(KLinePointerEvent.Up(event.x.toDouble(), event.y.toDouble()))
                platform.resetGestureClaim()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                platform.dispatchPointer(KLinePointerEvent.Cancel(event.x.toDouble(), event.y.toDouble()))
                platform.resetGestureClaim()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        platform.onDetached()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // attachIfNeeded restores when detach previously saved state
        platform.attachIfNeeded(width, height)
    }

    companion object {
        const val VIEW_NAME: String = KLineChartView.VIEW_NAME
    }
}
