package com.kuikly.kuiklyklinechart.shared.demo

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuiklybase.kline.view.KLineChart

/**
 * Minimal library-capability sample: DSL [KLineChart] only — no AI card / quote chrome.
 * Open via router page name `MinimalLibrarySample`.
 */
@Page("MinimalLibrarySample")
internal class MinimalLibrarySamplePage : ShowcasePage() {
    override val pageTitle = "库能力样例"

    private val barsJson = encodeBars(RandomBarGenerator.defaultSymbolBars(count = 180, seed = 700L))

    override fun content(): ViewBuilder {
        val page = this
        return {
            View {
                attr { flex(1f); backgroundColor(Color(0xFFF8FAFC)) }
                Text {
                    attr {
                        text("KLineChart · MA + VOL · 日K")
                        fontSize(13f)
                        color(Color(0xFF64748B))
                        margin(12f)
                    }
                }
                View {
                    attr { flex(1f); marginLeft(8f); marginRight(8f); marginBottom(12f); backgroundColor(Color.WHITE) }
                    KLineChart {
                        attr {
                            flex(1f)
                            backgroundColor(Color.WHITE)
                            symbol("00700", "腾讯控股")
                            period(1, "day")
                            mode("full")
                            theme("light")
                            bars(page.barsJson)
                            config(
                                """{"panes":[{"id":"price","kind":"price","order":0,"weight":3.0,"minHeight":160},{"id":"volume","kind":"indicator","order":1,"weight":1.0,"minHeight":64}],"indicators":[{"id":"ma5","template":"MA","paneId":"price","params":[5]},{"id":"ma10","template":"MA","paneId":"price","params":[10]},{"id":"vol","template":"VOL","paneId":"volume"}]}""",
                            )
                        }
                        event {
                            onVisibleRangeChange { _, _ -> }
                            onError { code, message -> println("MinimalLibrarySample error: $code $message") }
                        }
                    }
                }
            }
        }
    }
}
