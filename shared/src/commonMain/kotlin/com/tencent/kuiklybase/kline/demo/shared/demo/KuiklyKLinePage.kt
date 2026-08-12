package com.tencent.kuiklybase.kline.demo.shared.demo

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuiklybase.kline.view.KLineChart
import com.tencent.kuiklybase.kline.view.KLineChartView

@Page("KuiklyKLineDemo")
internal class KuiklyKLinePage : Pager() {
    private lateinit var chartRef: ViewRef<KLineChartView>
    private var darkTheme by observable(false)
    private var status by observable("Long press the chart to inspect a candle")

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flex(1f)
                paddingTop(ctx.pageData.safeAreaInsets.top)
                paddingBottom(ctx.pageData.safeAreaInsets.bottom)
                backgroundColor(if (ctx.darkTheme) Color(0xFF101318) else Color.WHITE)
            }

            View {
                attr {
                    height(52f)
                    paddingLeft(16f)
                    paddingRight(16f)
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                }
                Text {
                    attr {
                        text("Kuikly K-Line")
                        fontSize(18f)
                        fontWeight600()
                        color(if (ctx.darkTheme) Color.WHITE else Color(0xFF111827))
                    }
                }
                Text {
                    attr {
                        text("00700 · 1D")
                        fontSize(13f)
                        color(if (ctx.darkTheme) Color(0xFFA8AFBA) else Color(0xFF596273))
                    }
                }
            }

            KLineChart {
                ref { ctx.chartRef = it }
                attr {
                    flex(1f)
                    symbol("00700", "Tencent Holdings")
                    period(1, "day")
                    theme(if (ctx.darkTheme) "dark" else "light")
                }
                event {
                    onError { code, message -> ctx.status = "$code: $message" }
                    onCrosshairChange { timestamp, price ->
                        ctx.status = if (timestamp == null || price == null) {
                            "Long press the chart to inspect a candle"
                        } else {
                            "$timestamp  ·  ${price.toString()}"
                        }
                    }
                }
            }

            Text {
                attr {
                    text(ctx.status)
                    fontSize(12f)
                    color(if (ctx.darkTheme) Color(0xFFA8AFBA) else Color(0xFF596273))
                    marginLeft(16f)
                    marginRight(16f)
                    marginTop(8f)
                    marginBottom(8f)
                }
            }

            View {
                attr {
                    height(52f)
                    paddingLeft(12f)
                    paddingRight(12f)
                    flexDirectionRow()
                    alignItemsCenter()
                }
                ctx.actionButton("Reset", ctx.darkTheme) { ctx.chartRef.view?.resetViewport() }.invoke(this)
                ctx.actionButton(if (ctx.darkTheme) "Light" else "Dark", ctx.darkTheme) {
                    ctx.darkTheme = !ctx.darkTheme
                }.invoke(this)
                ctx.actionButton("Latest", ctx.darkTheme) { ctx.chartRef.view?.scrollToLatest() }.invoke(this)
            }
        }
    }

    private fun actionButton(
        title: String,
        darkTheme: Boolean,
        action: () -> Unit,
    ): ViewBuilder = {
        Button {
            attr {
                flex(1f)
                height(36f)
                marginLeft(4f)
                marginRight(4f)
                borderRadius(4f)
                backgroundColor(if (darkTheme) Color(0xFF252B34) else Color.WHITE)
                border(Border(1f, BorderStyle.SOLID, if (darkTheme) Color(0xFF3A414D) else Color(0xFFD6DAE1)))
                titleAttr {
                    text(title)
                    fontSize(13f)
                    color(if (darkTheme) Color.WHITE else Color(0xFF111827))
                }
                highlightBackgroundColor(if (darkTheme) Color(0xFF343B46) else Color(0xFFE8EBF0))
            }
            event { click { action() } }
        }
    }
}
