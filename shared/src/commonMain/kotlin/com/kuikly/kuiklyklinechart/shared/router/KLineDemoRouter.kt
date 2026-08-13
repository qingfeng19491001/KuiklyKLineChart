package com.kuikly.kuiklyklinechart.shared.router

import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.kuikly.kuiklyklinechart.shared.canvas.KLineCanvasAdapter
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbarAction
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbar
import com.kuikly.kuiklyklinechart.shared.canvas.KLineToolbarButton
import com.kuikly.kuiklyklinechart.shared.demo.BasicKLinePage
import com.kuikly.kuiklyklinechart.shared.demo.ErrorHandlePage
import com.kuikly.kuiklyklinechart.shared.demo.IndicatorSwitchPage
import com.kuikly.kuiklyklinechart.shared.demo.InteractionShowcasePage
import com.kuikly.kuiklyklinechart.shared.demo.OverlayDrawPage

interface DemoPage {
    val title: String get() = this::class.simpleName ?: "DemoPage"
    val toolbarActions: List<KLineToolbarAction> get() = emptyList()

    fun start() {}
    fun onToolbarAction(actionId: String) {}
    fun onSizeChanged(width: Int, height: Int) {}
    fun draw(canvas: KLineCanvasAdapter)
    fun onPointer(x: Double, y: Double, kind: PointerKind)
    fun dispose() {}
}

enum class PointerKind {
    DOWN, MOVE, UP, CANCEL,
}

data class DemoEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val factory: () -> DemoPage,
)

object KLineDemoRouter {
    val entries: List<DemoEntry> = listOf(
        DemoEntry("basic", "基础 K 线页", "主图 MA + VOL 副图 / 平移缩放 / 十字光标 / 切换主题") { BasicPageDemoAdapter() },
        DemoEntry("indicators", "指标切换", "MA / BOLL / EXPMA 轮动，副图 VOL / MACD / KDJ 轮动") { IndicatorSwitchPage() },
        DemoEntry("overlays", "画线系统", "趋势线 / 水平线 / 文本标注，控制点拖拽，磁铁吸附") { OverlayDrawPage() },
        DemoEntry("interaction", "交互演示", "交互状态机演示：画线拖拽 > 十字光标 > 分隔条 > 缩放 > 平移") { InteractionShowcasePage() },
        DemoEntry("error", "错误与重试", "加载失败模拟、手动重试、前后分页、边界场景") { ErrorHandlePage() },
    )

    fun create(id: String): DemoPage? = entries.firstOrNull { it.id == id }?.factory?.invoke()
}

abstract class KLineBaseDemoPage(protected val controller: KLineChartController = KLineChartController()) : DemoPage {
    protected var viewportWidth: Int = 0
    protected var viewportHeight: Int = 0
    protected var toolbarButtons: List<KLineToolbarButton> = emptyList()
    protected var useLightTheme: Boolean = true

    override fun onSizeChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    protected fun drawToolbar(canvas: KLineCanvasAdapter, selected: Set<String> = emptySet()): List<KLineToolbarButton> {
        val buttons = KLineToolbar.layoutBottom(
            canvas,
            viewportWidth.toDouble(),
            viewportHeight.toDouble(),
            toolbarActions,
            selected,
        )
        toolbarButtons = buttons
        return buttons
    }

    protected fun hitToolbar(x: Double, y: Double): KLineToolbarAction? =
        toolbarButtons.firstOrNull { it.hit(x, y) }?.action

    protected fun applyTheme(light: Boolean) {
        useLightTheme = light
        controller.setTheme(if (light) KLineTheme.LIGHT else KLineTheme.DARK)
    }
}

private class BasicPageDemoAdapter : KLineBaseDemoPage() {
    private val inner = BasicKLinePage(controller = controller)
    override val title: String get() = "基础 K 线页"
    override val toolbarActions: List<KLineToolbarAction> = listOf(
        KLineToolbarAction("theme_light", "☀️ Light"),
        KLineToolbarAction("theme_dark", "🌙 Dark"),
        KLineToolbarAction("reset", "⟲ Reset"),
    )

    override fun start() = inner.start()
    override fun onSizeChanged(width: Int, height: Int) {
        super.onSizeChanged(width, height)
        inner.onSize(width, (height - KLineToolbar.HEIGHT).toInt())
    }

    override fun onToolbarAction(actionId: String) {
        when (actionId) {
            "theme_light" -> applyTheme(true)
            "theme_dark" -> applyTheme(false)
            "reset" -> controller.resetViewport()
        }
    }

    override fun draw(canvas: KLineCanvasAdapter) {
        inner.draw(canvas)
        drawToolbar(
            canvas,
            selected = if (useLightTheme) setOf("theme_light") else setOf("theme_dark"),
        )
    }

    override fun onPointer(x: Double, y: Double, kind: PointerKind) {
        val hit = hitToolbar(x, y)
        if (hit != null && kind == PointerKind.UP) {
            onToolbarAction(hit.id); return
        }
        val e: com.tencent.kuiklybase.kline.KLinePointerEvent = when (kind) {
            PointerKind.DOWN -> com.tencent.kuiklybase.kline.KLinePointerEvent.Down(x, y)
            PointerKind.MOVE -> com.tencent.kuiklybase.kline.KLinePointerEvent.Move(x, y)
            PointerKind.UP -> com.tencent.kuiklybase.kline.KLinePointerEvent.Up(x, y)
            PointerKind.CANCEL -> com.tencent.kuiklybase.kline.KLinePointerEvent.Cancel(x, y)
        }
        inner.view.onPointerEvent(e)
    }

    override fun dispose() = inner.dispose()
}
