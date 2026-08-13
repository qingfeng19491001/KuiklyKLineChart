package com.kuikly.kuiklyklinechart.shared.demo

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.BoxShadow
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ColorStop
import com.tencent.kuikly.core.base.Direction
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.view.KLineChart

private val showcaseBars = RandomBarGenerator.defaultSymbolBars(count = 120)
private val showcaseBarsJson = encodeBars(showcaseBars)

private enum class DemoPeriod(val label: String, val span: Int, val unit: String, val line: Boolean = false) {
    TIME("分时", 1, "minute", true), FIVE_DAY("五日", 5, "day", true), DAY("日K", 1, "day"), WEEK("周K", 1, "week"), MONTH("月K", 1, "month"),
    MIN_1("1分", 1, "minute"), MIN_5("5分", 5, "minute"), MIN_15("15分", 15, "minute"), MIN_30("30分", 30, "minute"), MIN_60("60分", 60, "minute"), MIN_120("120分", 120, "minute"), QUARTER("季K", 3, "month"), YEAR("年K", 12, "month")
}
private val periodBarsJson = DemoPeriod.entries.associateWith { period ->
    encodeBars(demoPeriodBars(period.span, period.unit, period.line))
}

internal fun demoPeriodBars(span: Int, unit: String, line: Boolean): List<KLineBar> {
    val fiveDayLine = line && unit == "day" && span == 5
    val spacing = when {
        fiveDayLine -> 5L * 60_000L
        unit == "minute" -> span * 60_000L
        unit == "week" -> 7L * 86_400_000L
        unit == "month" -> span * 30L * 86_400_000L
        else -> span * 86_400_000L
    }
    val seed = unit.fold(1_337L) { value, char -> value * 31L + char.code } * 31L + span
    return RandomBarGenerator.defaultSymbolBars(count = if (line) 180 else 120, seed = seed).mapIndexed { index, bar ->
        val timestamp = if (fiveDayLine) {
            val barsPerDay = 36
            1_700_000_000_000L + (index / barsPerDay) * 86_400_000L + (index % barsPerDay) * spacing
        } else {
            1_700_000_000_000L + index * spacing
        }
        bar.copy(timestamp = timestamp)
    }
}

internal fun demoDaySignalBar(): KLineBar = demoPeriodBars(span = 1, unit = "day", line = false)[90]
private enum class DemoMainIndicator(val label: String, val template: String?, val params: List<Double> = emptyList()) {
    BARE("裸K", null), MA("MA", "MA", listOf(5.0, 10.0, 20.0, 30.0)), BOLL("BOLL", "BOLL"), EXPMA("EXPMA", "EXPMA"), BBI("BBI", "BBI"), ENE("ENE", "ENE")
}
private enum class DemoFirstIndicator(val label: String, val template: String) {
    VOLUME("成交量", "VOL"), MACD("MACD", "MACD"), AMOUNT("成交额", "AMOUNT")
}
private enum class DemoSecondIndicator(val label: String, val template: String) {
    MACD("MACD", "MACD"), KDJ("KDJ", "KDJ"), RSI("RSI", "RSI"), WR("WR", "WR"), BBD("BBD", "BBD")
}
private enum class DemoMenu { PERIOD, MAIN, FIRST, SECOND }

internal data class DemoPopupPlacement(
    val top: Float,
    val opensUpward: Boolean,
)

internal fun demoPopupPlacement(
    anchorTop: Float,
    anchorHeight: Float,
    optionCount: Int,
    containerHeight: Float,
): DemoPopupPlacement {
    val menuHeight = optionCount * 27f + 10f
    val belowTop = anchorTop + anchorHeight
    return if (belowTop + menuHeight <= containerHeight) {
        DemoPopupPlacement(top = belowTop, opensUpward = false)
    } else {
        DemoPopupPlacement(top = (anchorTop - menuHeight).coerceAtLeast(0f), opensUpward = true)
    }
}

internal fun demoPeriodMenuTop(tabBarHeight: Float): Float = tabBarHeight

internal data class DemoQuoteMetric(val label: String, val value: String)

internal fun demoQuoteMetrics(): List<DemoQuoteMetric> = listOf(
    DemoQuoteMetric("高", "489.80"),
    DemoQuoteMetric("总值", "3.82万亿"),
    DemoQuoteMetric("量比", "1.12"),
    DemoQuoteMetric("低", "475.60"),
    DemoQuoteMetric("流通", "3.82万亿"),
    DemoQuoteMetric("换手", "0.21%"),
    DemoQuoteMetric("开", "477.20"),
    DemoQuoteMetric("量", "1823万"),
    DemoQuoteMetric("额", "88.42亿"),
)

internal fun compactShowcaseFields() = listOf("股票", "最新价", "涨跌幅", "周期", "AI摘要", "详情入口")

internal fun signalDetailFields() =
    listOf("最新价", "涨跌幅", "最高", "最低", "今开", "成交量", "信号", "置信度", "关键位", "风险")

@Page("router", supportInLocal = true)
internal class RouterPage : Pager() {
    private var inputText = ""
    private lateinit var inputRef: ViewRef<InputView>

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f); backgroundColor(Color.WHITE) }
            View {
                attr { paddingTop(ctx.pageData.statusBarHeight); backgroundColor(Color.WHITE) }
                View {
                    attr { height(44f); allCenter() }
                    Text {
                        attr {
                            text("Kuikly页面路由"); fontSize(17f); fontWeightSemisolid()
                            backgroundLinearGradient(
                                Direction.TO_BOTTOM,
                                ColorStop(Color(0xFF23D3FD), 0f),
                                ColorStop(Color(0xFFAD37FE), 1f),
                            )
                        }
                    }
                }
            }
            View {
                attr { allCenter(); margin(20f) }
                View {
                    attr {
                        backgroundColor(Color.WHITE); borderRadius(10f); padding(10f)
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("kuikly-logo.png"))
                            size(
                                ctx.pageData.pageViewWidth * 0.6f,
                                ctx.pageData.pageViewWidth * 0.6f * (1987f / 2894f),
                            )
                        }
                    }
                }
            }
            View {
                attr { flexDirectionRow() }
                View {
                    attr { margin(10f); marginTop(0f); flex(1f); height(40f); borderRadius(5f) }
                    View {
                        attr {
                            absolutePositionAllZero()
                            backgroundLinearGradient(
                                Direction.TO_LEFT,
                                ColorStop(Color(0xFF23D3FD), 0f),
                                ColorStop(Color(0xFFAD37FE), 1f),
                            )
                        }
                        View { attr { absolutePosition(top = 1f, left = 1f, right = 1f, bottom = 1f); backgroundColor(Color.WHITE); borderRadius(5f) } }
                    }
                    Input {
                        ref { ctx.inputRef = it }
                        attr {
                            flex(1f); marginLeft(10f); marginRight(10f); fontSize(15f); color(Color(0xFFAD37FE))
                            placeholder("输入pageName"); autofocus(true); placeholderColor(Color(0xAA23D3FD))
                        }
                        event { textDidChange { ctx.inputText = it.text } }
                    }
                }
                Button {
                    attr {
                        size(80f, 40f); marginLeft(2f); marginRight(15f); borderRadius(20f)
                        backgroundLinearGradient(
                            Direction.TO_BOTTOM,
                            ColorStop(Color(0xAA23D3FD), 0f),
                            ColorStop(Color(0xAAAD37FE), 1f),
                        )
                        titleAttr { text("跳转"); fontSize(17f); color(Color.WHITE) }
                    }
                    event {
                        click {
                            val pageName = ctx.inputText.trim()
                            if (pageName.isNotEmpty()) {
                                ctx.inputRef.view?.blur()
                                ctx.openPage(pageName)
                            }
                        }
                    }
                }
            }
            Text {
                attr {
                    text("输入规则：router 或者 router&key=value (&后面为页面参数)"); fontSize(15f); marginLeft(10f); marginTop(5f)
                    backgroundLinearGradient(
                        Direction.TO_RIGHT,
                        ColorStop(Color(0xFFAD37FE), 0f),
                        ColorStop(Color(0xFF23D3FD), 1f),
                    )
                }
            }
            ctx.entry("FullChartDemo", "📈 完整 K 线 Demo", 220f, Color(0xFF4F8FFF), Color(0xFF6C5CE7), Color(0x334F8FFF)).invoke(this)
            ctx.entry("CompactChartDemo", "💬 迷你 K 线卡片 Demo", 260f, Color(0xFF6C5CE7), Color(0xFFA29BFE), Color(0x336C5CE7)).invoke(this)
            ctx.entry("SignalOverlayDemo", "✨ AI 信号叠加 Demo", 260f, Color(0xFF00B894), Color(0xFF00CEC9), Color(0x3300B894)).invoke(this)
        }
    }

    private fun entry(page: String, title: String, width: Float, start: Color, end: Color, shadow: Color): ViewBuilder = {
        View {
            attr { allCenter(); marginLeft(20f); marginRight(20f); marginBottom(20f) }
            View {
                attr {
                    size(width, 48f); borderRadius(24f); allCenter()
                    backgroundLinearGradient(Direction.TO_RIGHT, ColorStop(start, 0f), ColorStop(end, 1f))
                    boxShadow(BoxShadow(0f, 4f, 12f, shadow))
                }
                Text { attr { text(title); fontSize(18f); fontWeightSemisolid(); color(Color.WHITE) } }
                event { click { this@RouterPage.openPage(page) } }
            }
        }
    }

    private fun openPage(page: String) {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(page)
    }

}

internal abstract class ShowcasePage : Pager() {
    protected abstract val pageTitle: String
    protected abstract fun content(): ViewBuilder

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f); backgroundColor(Color.WHITE) }
            View {
                attr { paddingTop(ctx.pageData.statusBarHeight); backgroundColor(Color.WHITE) }
                View {
                    attr { height(44f); allCenter() }
                    Text {
                        attr { text(ctx.pageTitle); fontSize(17f); fontWeightSemisolid(); color(Color(0xFF333333)) }
                    }
                }
                View {
                    attr {
                        positionAbsolute(); top(ctx.pageData.statusBarHeight + 10f); left(12f)
                        size(24f, 24f); allCenter()
                    }
                    Text { attr { text("←"); fontSize(20f); color(Color(0xFF333333)) } }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                }
            }
            ctx.content().invoke(this)
        }
    }

    protected fun openShowcase(pageName: String) {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(pageName)
    }
}

@Page("FullChartDemo")
internal class FullChartDemo : ShowcasePage() {
    override val pageTitle = "腾讯控股  00700"
    private var selectedPeriod by observable(DemoPeriod.DAY)
    private var mainIndicator by observable(DemoMainIndicator.MA)
    private var firstIndicator by observable(DemoFirstIndicator.VOLUME)
    private var secondIndicator by observable(DemoSecondIndicator.MACD)
    private var openMenu by observable<DemoMenu?>(null)
    private var priceHeaderTop by observable(0f)
    private var firstHeaderTop by observable(0f)
    private var secondHeaderTop by observable(0f)
    private var aiTitle by observable("AI 趋势判断 · 短期偏强")
    private var aiSummary by observable("价格重新站上短期均线，关注 475.60 支撑位；若跌破应控制仓位风险。")
    private val aiSignalsJson = run {
        val bar = demoDaySignalBar()
        """[{"id":"detail-buy","timestamp":${bar.timestamp},"value":${bar.low},"type":"BUY","title":"AI 买入信号 · 置信度 82%","summary":"短期动能改善并重新站上均线，关注 475.60 支撑位并设置止损。","confidence":0.82}]"""
    }

    override fun content(): ViewBuilder {
        val page = this
        return {
            View {
                attr {
                    height(104f); paddingLeft(12f); paddingRight(12f); paddingTop(10f); paddingBottom(8f)
                    backgroundColor(Color.WHITE); flexDirectionRow(); alignItemsCenter()
                }
                View {
                    attr { width(120f); height(86f); justifyContentCenter() }
                    Text { attr { text("486.20"); fontSize(34f); fontWeight600(); color(Color(0xFFEF4444)) } }
                    View {
                        attr { height(22f); flexDirectionRow(); alignItemsCenter(); marginTop(2f) }
                        Text { attr { text("+8.40"); fontSize(13f); fontWeight600(); color(Color(0xFFEF4444)) } }
                        Text { attr { text("+1.76%"); fontSize(13f); fontWeight600(); marginLeft(8f); color(Color(0xFFEF4444)) } }
                    }
                }
                View {
                    attr { flex(1f); height(86f); marginLeft(4f); justifyContentCenter() }
                    demoQuoteMetrics().chunked(3).forEach { row ->
                        View {
                            attr { height(27f); flexDirectionRow(); alignItemsCenter() }
                            row.forEach { metric -> page.quoteMetric(metric.label, metric.value).invoke(this) }
                        }
                    }
                }
            }
            View {
                attr { height(34f) }
                View {
                    attr { absolutePositionAllZero(); flexDirectionRow(); alignItemsCenter(); borderBottom(Border(1f, BorderStyle.SOLID, Color(0xFFE5E7EB))) }
                    listOf(DemoPeriod.TIME, DemoPeriod.FIVE_DAY, DemoPeriod.DAY, DemoPeriod.WEEK, DemoPeriod.MONTH).forEach { period ->
                        vbind({ page.selectedPeriod == period }) {
                            View {
                                attr { flex(1f); height(34f); allCenter() }
                                event { click { page.selectPeriod(period) } }
                                Text { attr { text(period.label); fontSize(12f); color(if (page.selectedPeriod == period) Color(0xFF1677FF) else Color(0xFF8C8C8C)); if (page.selectedPeriod == period) fontWeight600() } }
                            }
                        }
                    }
                    View {
                        attr { flex(1f); height(34f); allCenter(); flexDirectionRow() }
                        event { click { page.openMenu = if (page.openMenu == DemoMenu.PERIOD) null else DemoMenu.PERIOD } }
                        Text {
                            attr {
                                text(if (page.selectedPeriod.ordinal > DemoPeriod.MONTH.ordinal) page.selectedPeriod.label else "更多")
                                fontSize(12f); color(if (page.selectedPeriod.ordinal > DemoPeriod.MONTH.ordinal) Color(0xFF1677FF) else Color(0xFF8C8C8C))
                            }
                        }
                        Text { attr { text("▾"); fontSize(9f); marginLeft(2f); color(if (page.selectedPeriod.ordinal > DemoPeriod.MONTH.ordinal) Color(0xFF1677FF) else Color(0xFF8C8C8C)) } }
                    }
                }
                vif({ page.openMenu == DemoMenu.PERIOD }) {
                    page.periodMenu().invoke(this)
                }
            }
            View {
                attr { flex(1f) }
                vbind({ listOf(page.selectedPeriod.name, page.mainIndicator.name, page.firstIndicator.name, page.secondIndicator.name, page.openMenu?.name).joinToString() }) {
                    KLineChart {
                        attr {
                            flex(1f); touchEnable(page.openMenu == null); symbol("00700", "Tencent Holdings")
                            period(page.selectedPeriod.span, page.selectedPeriod.unit)
                            mode("full"); priceStyle(if (page.selectedPeriod.line) "line" else "candle"); bars(periodBarsJson.getValue(page.selectedPeriod)); config(page.fullConfig())
                            signals(if (page.selectedPeriod == DemoPeriod.DAY) page.aiSignalsJson else "[]")
                        }
                        event {
                            onPaneLayoutChange { price, first, second ->
                                page.priceHeaderTop = price
                                page.firstHeaderTop = first
                                page.secondHeaderTop = second
                            }
                            onPaneHeaderClick { paneId ->
                                val menu = when (paneId) {
                                    "price" -> DemoMenu.MAIN
                                    "first" -> DemoMenu.FIRST
                                    "second" -> DemoMenu.SECOND
                                    else -> null
                                }
                                if (menu != null) page.openMenu = if (page.openMenu == menu) null else menu
                            }
                            onSignalClick { _, title, summary ->
                                page.aiTitle = title
                                page.aiSummary = summary
                            }
                        }
                    }
                }
                vbind({ "${page.priceHeaderTop}:${page.mainIndicator.name}" }) {
                    page.paneMenuButton(page.mainIndicator.label, DemoMenu.MAIN, page.priceHeaderTop).invoke(this)
                }
                vbind({ "${page.firstHeaderTop}:${page.firstIndicator.name}" }) {
                    page.paneMenuButton(page.firstIndicator.label, DemoMenu.FIRST, page.firstHeaderTop).invoke(this)
                }
                vbind({ "${page.secondHeaderTop}:${page.secondIndicator.name}" }) {
                    page.paneMenuButton(page.secondIndicator.label, DemoMenu.SECOND, page.secondHeaderTop).invoke(this)
                }
                vif({ page.openMenu != null }) {
                    View {
                        attr { positionAbsolute(); absolutePositionAllZero(); zIndex(45) }
                        event { click { page.openMenu = null } }
                    }
                }
                vif({ page.openMenu == DemoMenu.MAIN }) { page.optionColumn(DemoMainIndicator.entries, page.priceHeaderTop, page.chartViewportHeight(), { it.label }, { it == page.mainIndicator }) { page.mainIndicator = it }.invoke(this) }
                vif({ page.openMenu == DemoMenu.FIRST }) { page.optionColumn(DemoFirstIndicator.entries, page.firstHeaderTop, page.chartViewportHeight(), { it.label }, { it == page.firstIndicator }) { page.firstIndicator = it }.invoke(this) }
                vif({ page.openMenu == DemoMenu.SECOND }) {
                    page.optionColumn(
                        DemoSecondIndicator.entries,
                        page.secondHeaderTop,
                        page.chartViewportHeight(),
                        { it.label },
                        { it == page.secondIndicator },
                    ) { page.secondIndicator = it }.invoke(this)
                }
            }
            View {
                attr {
                    height(112f); margin(10f); marginTop(6f); padding(12f); borderRadius(10f)
                    backgroundColor(Color(0xFFFFF7ED)); boxShadow(BoxShadow(0f, 2f, 8f, Color(0x14000000)))
                }
                View {
                    attr { height(22f); flexDirectionRow(); alignItemsCenter() }
                    Text { attr { text("AI 行情解读"); fontSize(14f); fontWeight600(); color(Color(0xFF9A3412)) } }
                    View { attr { marginLeft(8f); paddingLeft(7f); paddingRight(7f); height(20f); allCenter(); borderRadius(10f); backgroundColor(Color(0xFFFFEDD5)) }; Text { attr { text("仅供参考"); fontSize(9f); color(Color(0xFFC2410C)) } } }
                    View { attr { flex(1f) } }
                    Text { attr { text("查看详情 ›"); fontSize(11f); color(Color(0xFF1677FF)) } }
                }
                Text { attr { text(page.aiTitle); fontSize(12f); fontWeight600(); marginTop(5f); color(Color(0xFF7C2D12)) } }
                Text { attr { text(page.aiSummary); fontSize(10f); marginTop(4f); color(Color(0xFF57534E)) } }
                event { click { page.openShowcase("SignalOverlayDemo") } }
            }
        }
    }

    private fun quoteMetric(label: String, value: String): ViewBuilder = {
        View {
            attr { flex(1f); height(27f); flexDirectionRow(); alignItemsCenter() }
            Text { attr { text(label); fontSize(9f); color(Color(0xFF8A94A6)); width(23f) } }
            Text { attr { text(value); fontSize(10f); fontWeight600(); color(Color(0xFF374151)) } }
        }
    }

    private fun paneMenuButton(title: String, menu: DemoMenu, top: Float): ViewBuilder {
        val page = this
        return {
          View {
            attr {
                positionAbsolute(); left(0f); top(top); zIndex(40); size(76f, 18f)
                flexDirectionRow(); alignItemsCenter(); paddingLeft(4f); borderRadius(4f)
                backgroundColor(Color(0xFFF5F5F5))
            }
            event { click { page.openMenu = if (page.openMenu == menu) null else menu } }
            Text { attr { text("$title ▾"); fontSize(10f); color(Color(0xFF595959)) } }
          }
        }
    }

    private fun selectPeriod(period: DemoPeriod) {
        selectedPeriod = period
        openMenu = null
        aiTitle = if (period == DemoPeriod.DAY) "AI 趋势判断 · 短期偏强" else "AI 趋势判断 · 等待当前周期信号"
        aiSummary = if (period == DemoPeriod.DAY) {
            "价格重新站上短期均线，关注 475.60 支撑位；若跌破应控制仓位风险。"
        } else {
            "当前周期暂无高置信度信号，可切换日 K 查看示例买卖点与风险解读。"
        }
    }

    private fun <T> optionColumn(
        options: List<T>,
        anchorTop: Float,
        containerHeight: Float,
        label: (T) -> String,
        selected: (T) -> Boolean,
        select: (T) -> Unit,
    ): ViewBuilder {
        val page = this
        val placement = demoPopupPlacement(anchorTop, 18f, options.size, containerHeight)
        return {
          View {
            attr {
                positionAbsolute(); left(0f); top(placement.top); zIndex(50); width(82f); padding(5f)
                backgroundColor(Color.WHITE); borderRadius(7f); boxShadow(BoxShadow(0f, if (placement.opensUpward) -2f else 2f, 8f, Color(0x1F000000)))
            }
            options.forEach { option ->
                View {
                    attr { height(27f); allCenter(); borderRadius(5f); backgroundColor(if (selected(option)) Color(0x1A1677FF) else Color.TRANSPARENT) }
                    event { click { select(option); page.openMenu = null } }
                    Text { attr { text(label(option)); fontSize(11f); color(if (selected(option)) Color(0xFF1677FF) else Color(0xFF595959)) } }
                }
            }
          }
        }
    }

    private fun periodMenu(): ViewBuilder {
        val page = this
        val options = listOf(DemoPeriod.MIN_1, DemoPeriod.MIN_5, DemoPeriod.MIN_15, DemoPeriod.MIN_30, DemoPeriod.MIN_60, DemoPeriod.MIN_120, DemoPeriod.QUARTER, DemoPeriod.YEAR)
        return {
            View {
                attr {
                    positionAbsolute(); top(demoPeriodMenuTop(34f)); right(0f); zIndex(50); width(82f); padding(8f)
                    flexDirectionColumn(); borderRadius(8f); backgroundColor(Color.WHITE); boxShadow(BoxShadow(0f, 2f, 8f, Color(0x1F000000)))
                }
                options.forEach { period ->
                    View {
                        attr { height(30f); allCenter(); borderRadius(5f); backgroundColor(if (page.selectedPeriod == period) Color(0x1A1677FF) else Color.TRANSPARENT) }
                        event { click { page.selectPeriod(period) } }
                        Text { attr { text(period.label); fontSize(12f); color(if (page.selectedPeriod == period) Color(0xFF1677FF) else Color(0xFF595959)) } }
                    }
                }
            }
        }
    }

    private fun chartViewportHeight(): Float =
        (pageData.pageViewHeight - pageData.statusBarHeight - 44f - 104f - 34f - 128f).coerceAtLeast(340f)

    private fun fullConfig(): String {
        val main = mainIndicator.template?.let { template ->
            val params = if (mainIndicator.params.isEmpty()) "" else ",\"params\":[${mainIndicator.params.joinToString()}]"
            "{\"id\":\"main\",\"template\":\"$template\",\"paneId\":\"price\"$params},"
        }.orEmpty()
        return """{"panes":[
            {"id":"price","kind":"price","order":0,"weight":3.6,"minHeight":180},
            {"id":"first","kind":"indicator","order":1,"weight":1.15,"minHeight":74},
            {"id":"second","kind":"indicator","order":2,"weight":1.15,"minHeight":74}],
            "indicators":[$main
            {"id":"first-indicator","template":"${firstIndicator.template}","paneId":"first"},
            {"id":"second-indicator","template":"${secondIndicator.template}","paneId":"second"}]}""".replace("\n", "")
    }
}

@Page("CompactChartDemo")
internal class CompactChartDemo : ShowcasePage() {
    override val pageTitle = "AI 股票问答"
    override fun content(): ViewBuilder {
        val page = this
        return {
        View {
            attr { flex(1f); padding(14f); backgroundColor(Color(0xFFF5F7FA)) }
            Text { attr { text("今天"); fontSize(10f); alignSelfCenter(); color(Color(0xFF9CA3AF)); marginBottom(12f) } }
            View {
                attr { alignSelfFlexEnd(); maxWidth(286f); padding(12f); borderRadius(14f); backgroundColor(Color(0xFF1677FF)); marginBottom(14f) }
                Text { attr { text("腾讯控股最近走势怎么样？"); fontSize(14f); color(Color.WHITE) } }
            }
            View {
                attr { height(28f); flexDirectionRow(); alignItemsCenter(); marginBottom(8f) }
                View {
                    attr { size(28f, 28f); allCenter(); borderRadius(14f); backgroundColor(Color(0xFF1677FF)) }
                    Text { attr { text("AI"); fontSize(11f); fontWeight600(); color(Color.WHITE) } }
                }
                Text { attr { text("行情助手"); fontSize(12f); fontWeight600(); marginLeft(8f); color(Color(0xFF475569)) } }
            }
            View {
                attr { width(332f); padding(12f); borderRadius(14f); backgroundColor(Color.WHITE); boxShadow(BoxShadow(0f, 3f, 12f, Color(0x12000000))) }
                Text { attr { text("腾讯控股短期维持偏强走势，价格位于主要均线上方。"); fontSize(13f); color(Color(0xFF334155)); marginBottom(12f) } }
                View {
                    attr { height(46f); flexDirectionRow(); alignItemsCenter() }
                    View {
                        attr { flex(1f) }
                        Text { attr { text("腾讯控股"); fontSize(16f); fontWeight600(); color(Color(0xFF0F172A)) } }
                        Text { attr { text("00700 · 港股 · 日K"); fontSize(10f); marginTop(3f); color(Color(0xFF94A3B8)) } }
                    }
                    View {
                        attr { alignItemsFlexEnd() }
                        Text { attr { text("486.20"); fontSize(22f); fontWeight600(); color(Color(0xFFEF4444)) } }
                        Text { attr { text("+8.40  +1.76%"); fontSize(10f); marginTop(2f); color(Color(0xFFEF4444)) } }
                    }
                }
                View {
                    attr { height(158f); marginTop(5f); backgroundColor(Color(0xFFF8FAFC)); borderRadius(10f); padding(6f) }
                    KLineChart { attr { flex(1f); symbol("00700"); period(1, "day"); mode("compact"); bars(showcaseBarsJson) } }
                }
                View {
                    attr { marginTop(10f); padding(10f); borderRadius(8f); backgroundColor(Color(0xFFF0F7FF)) }
                    Text { attr { text("AI 摘要"); fontSize(10f); fontWeight600(); color(Color(0xFF1677FF)) } }
                    Text { attr { text("动能改善，但临近前高后波动可能增大，关注 475.60 支撑位。"); fontSize(11f); marginTop(4f); color(Color(0xFF475569)) } }
                }
                View {
                    attr { height(38f); flexDirectionRow(); alignItemsCenter(); marginTop(6f); borderTop(Border(1f, BorderStyle.SOLID, Color(0xFFEFF3F8))) }
                    Text { attr { text("查看详情、完整 K 线与 AI 信号"); fontSize(12f); color(Color(0xFF1677FF)) } }
                    View { attr { flex(1f) } }
                    Text { attr { text("›"); fontSize(20f); color(Color(0xFF1677FF)) } }
                    event { click { page.openShowcase("SignalOverlayDemo") } }
                }
            }
            Text { attr { text("AI 内容仅供参考，不构成投资建议"); fontSize(10f); marginTop(10f); marginLeft(36f); color(Color(0xFF9CA3AF)) } }
            View { attr { flex(1f) } }
            View {
                attr { height(46f); flexDirectionRow(); alignItemsCenter(); paddingLeft(14f); paddingRight(8f); borderRadius(23f); backgroundColor(Color.WHITE); border(Border(1f, BorderStyle.SOLID, Color(0xFFE2E8F0))) }
                Text { attr { text("继续问这只股票..."); fontSize(13f); color(Color(0xFF94A3B8)) } }
                View { attr { flex(1f) } }
                View { attr { size(34f, 34f); allCenter(); borderRadius(17f); backgroundColor(Color(0xFF1677FF)) }; Text { attr { text("↑"); fontSize(17f); fontWeight600(); color(Color.WHITE) } } }
            }
        }
        }
    }
}

@Page("SignalOverlayDemo")
internal class SignalOverlayDemo : ShowcasePage() {
    override val pageTitle = "腾讯控股  00700"
    private var selectedTitle by observable("AI 买入信号 · 置信度 82%")
    private var selectedSummary by observable("短期动能改善并重新站上均线，建议结合 475.60 支撑位控制风险。点击图中 BUY 标记可联动更新解读。")
    private var signalClickCount by observable(0)
    private val signalBar = showcaseBars[90]
    private val signalsJson = """[{"id":"buy-1","timestamp":${signalBar.timestamp},"value":${signalBar.low},"type":"BUY","title":"AI 买入信号","summary":"短期动能改善并重新站上均线，建议结合 475.60 支撑位控制风险。","confidence":0.82}]"""

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { height(72f); paddingLeft(14f); paddingRight(14f); flexDirectionRow(); alignItemsCenter(); backgroundColor(Color.WHITE) }
                View {
                    attr { width(116f) }
                    Text { attr { text("486.20"); fontSize(28f); fontWeight600(); color(Color(0xFFEF4444)) } }
                    Text { attr { text("+8.40  +1.76%"); fontSize(11f); marginTop(2f); color(Color(0xFFEF4444)) } }
                }
                View { attr { flex(1f); height(48f); marginLeft(8f); justifyContentSpaceBetween() }; ctx.detailMetricRow("高 489.80", "低 475.60", "开 477.20").invoke(this); ctx.detailMetricRow("量 1823万", "额 88.42亿", "日K").invoke(this) }
            }
            KLineChart {
                attr { height(376f); symbol("00700", "腾讯控股"); period(1, "day"); mode("full"); bars(showcaseBarsJson); signals(ctx.signalsJson) }
                event { onSignalClick { _, title, summary -> ctx.signalClickCount += 1; ctx.selectedTitle = "$title · 已联动 ${ctx.signalClickCount} 次"; ctx.selectedSummary = summary } }
            }
            View {
                attr { margin(14f); padding(14f); borderRadius(12f); backgroundColor(Color.WHITE); border(Border(1f, BorderStyle.SOLID, Color(0xFFDCFCE7))); boxShadow(BoxShadow(0f, 3f, 10f, Color(0x10000000))) }
                View {
                    attr { height(24f); flexDirectionRow(); alignItemsCenter() }
                    View { attr { height(22f); paddingLeft(8f); paddingRight(8f); allCenter(); borderRadius(11f); backgroundColor(Color(0xFFDCFCE7)) }; Text { attr { text("买入信号"); fontSize(10f); fontWeight600(); color(Color(0xFF15803D)) } } }
                    View { attr { flex(1f) } }
                    Text { attr { text("置信度 82%"); fontSize(11f); fontWeight600(); color(Color(0xFF15803D)) } }
                }
                Text { attr { text(ctx.selectedTitle); fontSize(16f); fontWeight600(); color(Color(0xFF14532D)); marginTop(8f) } }
                Text { attr { text(ctx.selectedSummary); fontSize(13f); marginTop(6f); color(Color(0xFF374151)) } }
                View {
                    attr { height(48f); flexDirectionRow(); alignItemsCenter(); marginTop(10f); borderRadius(8f); backgroundColor(Color(0xFFF8FAFC)) }
                    ctx.signalMetric("趋势", "短期偏强", Color(0xFF15803D)).invoke(this)
                    ctx.signalMetric("关键位", "475.60", Color(0xFF0F172A)).invoke(this)
                    ctx.signalMetric("周期", "日K", Color(0xFF0F172A)).invoke(this)
                }
                View {
                    attr { height(1f); marginTop(10f); marginBottom(9f); backgroundColor(Color(0xFFE2E8F0)) }
                }
                Text { attr { text("风险提示  信号基于历史行情分析，不代表未来收益；跌破关键位应控制仓位。"); fontSize(11f); color(Color(0xFF64748B)) } }
            }
        }
    }

    private fun detailMetricRow(first: String, second: String, third: String): ViewBuilder = {
        View { attr { height(22f); flexDirectionRow(); alignItemsCenter() }; listOf(first, second, third).forEach { value -> Text { attr { text(value); flex(1f); fontSize(9f); color(Color(0xFF64748B)) } } } }
    }

    private fun signalMetric(label: String, value: String, valueColor: Color): ViewBuilder = {
        View { attr { flex(1f); allCenter() }; Text { attr { text(label); fontSize(9f); color(Color(0xFF94A3B8)) } }; Text { attr { text(value); fontSize(12f); fontWeight600(); marginTop(3f); color(valueColor) } } }
    }
}

private fun encodeBars(bars: List<KLineBar>): String = bars.joinToString(prefix = "[", postfix = "]") { bar ->
    """{"timestamp":${bar.timestamp},"open":${bar.open},"high":${bar.high},"low":${bar.low},"close":${bar.close},"volume":${bar.volume},"turnover":${bar.turnover}}"""
}
