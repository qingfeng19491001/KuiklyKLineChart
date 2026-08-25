package com.tencent.kuiklybase.kline.host

import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuiklybase.kline.view.KLineChartView

/** Registers the published Android host under the cross-platform view name. */
fun IKuiklyRenderExport.registerKuiklyKLineChart() {
    // Do not use trailing-lambda form; shadowExportCreator is the optional 3rd param.
    renderViewExport(KLineChartView.VIEW_NAME, { context -> AndroidKLineChartView(context) })
}
