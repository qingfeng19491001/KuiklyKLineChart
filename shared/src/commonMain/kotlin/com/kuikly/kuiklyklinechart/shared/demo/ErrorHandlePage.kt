package com.kuikly.kuiklyklinechart.shared.demo

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineCancelable
import com.tencent.kuiklybase.kline.data.KLineLoadCallback
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.data.KLineLoadPage
import com.tencent.kuiklybase.kline.data.KLineLoadRequest
import com.tencent.kuiklybase.kline.data.KLineLoadResult
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineRealtimeEvent
import com.tencent.kuiklybase.kline.data.KLineRealtimeListener
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.kuikly.kuiklyklinechart.shared.KLineChartView
import com.kuikly.kuiklyklinechart.shared.canvas.KLineCanvasAdapter
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbar
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbarAction
import com.kuikly.kuiklyklinechart.shared.router.KLineBaseDemoPage
import com.kuikly.kuiklyklinechart.shared.router.PointerKind
import kotlin.random.Random

class FlakyKLineDataSource(
    private val bars: List<KLineBar>,
    private val failureRate: Double = 0.5,
) : com.tencent.kuiklybase.kline.data.KLineDataSource {

    private val random = Random(flakySequence++)
    var forceFailNext: Boolean = false
    var lastRequest: KLineLoadRequest? = null; private set

    override fun load(request: KLineLoadRequest, callback: KLineLoadCallback): KLineCancelable {
        lastRequest = request
        if (forceFailNext || random.nextDouble() < failureRate) {
            forceFailNext = false
            callback.onResult(KLineLoadResult.Failure("网络错误：模拟请求失败，请重试 (${request.direction})"))
        } else {
            val sorted = bars.sortedBy { it.timestamp }
            val page = when (request.direction) {
                KLineLoadDirection.INITIAL -> {
                    val slice = sorted.takeLast(request.limit)
                    KLineLoadPage(slice, hasMoreBefore = slice.size < sorted.size, hasMoreAfter = false)
                }
                KLineLoadDirection.BEFORE -> {
                    val anchor = request.anchorTimestamp!!
                    val earlier = sorted.filter { it.timestamp < anchor }.takeLast(request.limit)
                    val hasMore = sorted.indexOfFirst { it.timestamp < anchor } > 0
                    KLineLoadPage(earlier, hasMoreBefore = hasMore, hasMoreAfter = false)
                }
                KLineLoadDirection.AFTER -> {
                    val anchor = request.anchorTimestamp!!
                    val later = sorted.filter { it.timestamp > anchor }.take(request.limit)
                    val hasMore = sorted.any { it.timestamp > (later.lastOrNull()?.timestamp ?: anchor) }
                    KLineLoadPage(later, hasMoreBefore = false, hasMoreAfter = hasMore)
                }
            }
            callback.onResult(KLineLoadResult.Success(page))
        }
        return KLineCancelable {}
    }

    override fun subscribe(symbol: KLineSymbol, period: KLinePeriod, listener: KLineRealtimeListener): KLineCancelable =
        KLineCancelable {}

    companion object { private var flakySequence: Int = 1 }
}

class ErrorHandlePage : KLineBaseDemoPage() {
    override val title: String = "错误处理与重试演示"
    override val toolbarActions: List<KLineToolbarAction> = listOf(
        KLineToolbarAction("retry", "↻ 重试加载"),
        KLineToolbarAction("fail_next", "💥 模拟下次失败"),
        KLineToolbarAction("load_before", "⏪ 加载更早"),
        KLineToolbarAction("reset", "⟲ 重置"),
    )

    private val baseBars = RandomBarGenerator.defaultSymbolBars(count = 600, seed = 5555L)
    private val flakySource = FlakyKLineDataSource(baseBars, failureRate = 0.4)
    private var lastError: String? = null
    private var successCount = 0
    private var failCount = 0

    private val view: KLineChartView = KLineChartView(flakySource, controller) {
        theme { com.tencent.kuiklybase.kline.config.KLineTheme.LIGHT }
        addDefaultIndicators()
        onError {
            lastError = it.message
            failCount += 1
        }
    }

    override fun start() {
        view.attach()
        controller.setMarket(KLineSymbol("FLAKY", "错误演示"), KLinePeriod(1, KLinePeriodUnit.DAY))
        successCount += 1
    }

    override fun onToolbarAction(actionId: String) {
        when (actionId) {
            "retry" -> {
                view.retryInitialLoad()
                successCount += 1
            }
            "fail_next" -> {
                flakySource.forceFailNext = true
            }
            "load_before" -> {
                view.triggerLoadBefore()
                successCount += 1
            }
            "reset" -> {
                lastError = null; successCount = 0; failCount = 0
                view.triggerLoadBefore()
            }
        }
    }

    override fun onSizeChanged(width: Int, height: Int) {
        super.onSizeChanged(width, height)
        view.onSizeChanged(width, (height - KLineToolbar.HEIGHT).toInt())
    }

    override fun draw(canvas: KLineCanvasAdapter) {
        view.draw(canvas)
        val status = buildString {
            append("成功 $successCount 次 / 失败 $failCount 次")
            if (!lastError.isNullOrBlank()) append("   最近错误：$lastError")
        }
        canvas.drawRect(8.0, 8.0, viewportWidth - 8.0, 40.0,
            if (lastError == null) "#E8F5E9CC" else "#FFEBEECC",
            strokeColor = if (lastError == null) "#2E7D3288" else "#C6282888", strokeWidth = 1.0, cornerRadius = 4.0)
        canvas.drawText(status, 16.0, 14.0, viewportWidth - 16.0, 36.0,
            color = if (lastError == null) "#1B5E20FF" else "#B71C1CFF", textSize = 12.0)
        drawToolbar(canvas)
    }

    override fun onPointer(x: Double, y: Double, kind: PointerKind) {
        val hit = hitToolbar(x, y)
        if (hit != null && kind == PointerKind.UP) { onToolbarAction(hit.id); return }
        view.onPointerEvent(IndicatorSwitchPage.pointerEvent(x, y, kind))
    }

    override fun dispose() = view.dispose()
}
