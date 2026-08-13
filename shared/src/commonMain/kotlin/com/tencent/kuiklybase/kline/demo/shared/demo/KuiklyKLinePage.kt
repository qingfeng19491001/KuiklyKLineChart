package com.tencent.kuiklybase.kline.demo.shared.demo

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.view.KLineChart

private val showcaseBars = RandomBarGenerator.defaultSymbolBars(count = 120)
private val showcaseBarsJson = encodeBars(showcaseBars)

@Page("KuiklyKLineDemo")
internal class KuiklyKLinePage : Pager() {
    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f); paddingTop(ctx.pageData.safeAreaInsets.top); backgroundColor(Color(0xFFF6F7F9)) }
            Text { attr { text("KuiklyKLineChart Showcases"); fontSize(20f); fontWeight600(); margin(20f); color(Color(0xFF111827)) } }
            ctx.entry("FullChartDemo", "Full chart", "Indicators, axes and viewport interaction").invoke(this)
            ctx.entry("CompactChartDemo", "Compact chart", "Lightweight AI reply card preset").invoke(this)
            ctx.entry("SignalOverlayDemo", "Signal overlay", "AI signal hit and interpretation card").invoke(this)
        }
    }

    private fun entry(page: String, title: String, subtitle: String): ViewBuilder = {
        Button {
            attr {
                height(72f); marginLeft(16f); marginRight(16f); marginBottom(12f); borderRadius(10f)
                backgroundColor(Color.WHITE)
                titleAttr { text("$title\n$subtitle"); fontSize(15f); color(Color(0xFF111827)) }
            }
            event { click { this@KuiklyKLinePage.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(page) } }
        }
    }
}

internal abstract class ShowcasePage : Pager() {
    protected abstract val pageTitle: String
    protected abstract fun content(): ViewBuilder

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f); paddingTop(ctx.pageData.safeAreaInsets.top); backgroundColor(Color.WHITE) }
            View {
                attr { height(48f); flexDirectionRow(); alignItemsCenter(); backgroundColor(Color.WHITE) }
                Text {
                    attr { text("←"); fontSize(24f); width(48f); textAlignCenter(); color(Color(0xFF111827)) }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                }
                Text { attr { text(ctx.pageTitle); fontSize(17f); fontWeight600(); color(Color(0xFF111827)) } }
            }
            ctx.content().invoke(this)
        }
    }
}

@Page("FullChartDemo")
internal class FullChartDemo : ShowcasePage() {
    override val pageTitle = "Full chart"
    override fun content(): ViewBuilder = {
        KLineChart {
            attr {
                flex(1f); symbol("00700", "Tencent Holdings"); period(1, "day")
                mode("full"); bars(showcaseBarsJson)
            }
        }
    }
}

@Page("CompactChartDemo")
internal class CompactChartDemo : ShowcasePage() {
    override val pageTitle = "Compact chart card"
    override fun content(): ViewBuilder = {
        View {
            attr { margin(16f); height(210f); borderRadius(12f); backgroundColor(Color(0xFFF8FAFC)); padding(12f) }
            Text { attr { text("00700  Tencent Holdings"); fontSize(15f); fontWeight600(); color(Color(0xFF111827)); height(28f) } }
            KLineChart { attr { flex(1f); symbol("00700"); period(1, "day"); mode("compact"); bars(showcaseBarsJson) } }
        }
    }
}

@Page("SignalOverlayDemo")
internal class SignalOverlayDemo : ShowcasePage() {
    override val pageTitle = "AI signal overlay"
    private var selectedTitle by observable("Tap the BUY signal")
    private var selectedSummary by observable("The interpretation card belongs to the business demo, not the chart component.")
    private val signalBar = showcaseBars[90]
    private val signalsJson = """[{"id":"buy-1","timestamp":${signalBar.timestamp},"value":${signalBar.low},"type":"BUY","title":"BUY","summary":"Momentum improved near this candle.","confidence":0.82}]"""

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            KLineChart {
                attr { height(360f); symbol("00700"); period(1, "day"); mode("full"); bars(showcaseBarsJson); signals(ctx.signalsJson) }
                event { onSignalClick { _, title, summary -> ctx.selectedTitle = title; ctx.selectedSummary = summary } }
            }
            View {
                attr { margin(16f); padding(14f); borderRadius(10f); backgroundColor(Color(0xFFF0FDF4)) }
                Text { attr { text(ctx.selectedTitle); fontSize(16f); fontWeight600(); color(Color(0xFF166534)); marginBottom(6f) } }
                Text { attr { text(ctx.selectedSummary); fontSize(13f); color(Color(0xFF374151)) } }
            }
        }
    }
}

private fun encodeBars(bars: List<KLineBar>): String = bars.joinToString(prefix = "[", postfix = "]") { bar ->
    """{"timestamp":${bar.timestamp},"open":${bar.open},"high":${bar.high},"low":${bar.low},"close":${bar.close},"volume":${bar.volume},"turnover":${bar.turnover}}"""
}
