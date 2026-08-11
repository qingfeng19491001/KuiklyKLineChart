package com.tencent.kuiklybase.kline.controller

import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.format.KLineFormatters
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.viewport.KLineViewport

class KLineChartState(
    val viewport: KLineViewport?,
    panes: List<KLinePane>,
    indicatorInstances: List<KLineIndicatorInstance>,
    overlayInstances: List<KLineOverlayInstance>,
    val theme: KLineTheme,
    val formatters: KLineFormatters = KLineFormatters.DEFAULT,
) {
    val panes = panes.map { it.copy(yAxes = it.yAxes.toList()) }
    val indicatorInstances = indicatorInstances.map { it.copy(params = it.params.toList()) }
    val overlayInstances = overlayInstances.map { it.deepCopy() }

    fun copy(
        viewport: KLineViewport? = this.viewport,
        panes: List<KLinePane> = this.panes,
        indicatorInstances: List<KLineIndicatorInstance> = this.indicatorInstances,
        overlayInstances: List<KLineOverlayInstance> = this.overlayInstances,
        theme: KLineTheme = this.theme,
        formatters: KLineFormatters = this.formatters,
    ) = KLineChartState(viewport, panes, indicatorInstances, overlayInstances, theme, formatters)

    override fun equals(other: Any?) = other is KLineChartState && viewport == other.viewport && panes == other.panes &&
        indicatorInstances == other.indicatorInstances && overlayInstances == other.overlayInstances && theme == other.theme &&
        formatters == other.formatters
    override fun hashCode(): Int {
        var result = viewport?.hashCode() ?: 0; result = 31 * result + panes.hashCode(); result = 31 * result + indicatorInstances.hashCode()
        result = 31 * result + overlayInstances.hashCode(); result = 31 * result + theme.hashCode()
        return 31 * result + formatters.hashCode()
    }
    override fun toString() = "KLineChartState(viewport=$viewport, panes=$panes, indicatorInstances=$indicatorInstances, overlayInstances=$overlayInstances, theme=$theme, formatters=$formatters)"
}

internal fun KLineOverlayInstance.deepCopy() = copy(
    points = points.toList(),
    styles = styles.mapValues { (_, value) -> value.copy(lineDash = value.lineDash.toList()) },
    extendData = extendData.toMap(),
)
