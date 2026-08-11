package com.tencent.kuiklybase.kline.config

enum class KLinePriceDirection { RED_UP_GREEN_DOWN, GREEN_UP_RED_DOWN }

data class KLineCandleTheme(val riseColor: String, val fallColor: String, val unchangedColor: String, val wickWidth: Double, val bodyWidthRatio: Double) {
    init { validateColor(riseColor, "Rise"); validateColor(fallColor, "Fall"); validateColor(unchangedColor, "Unchanged"); positive(wickWidth, "Candle wick width"); ratio(bodyWidthRatio, "Candle body width ratio") }
}
class KLineGridTheme(val color: String, val lineWidth: Double, lineDash: List<Double>) {
    val lineDash = lineDash.toList()
    init { validateColor(color, "Grid"); positive(lineWidth, "Grid line width"); this.lineDash.forEach { positive(it, "Grid line dash") } }
    override fun equals(other: Any?) = other is KLineGridTheme && color == other.color && lineWidth == other.lineWidth && lineDash == other.lineDash
    override fun hashCode() = 31 * (31 * color.hashCode() + lineWidth.hashCode()) + lineDash.hashCode()
    override fun toString() = "KLineGridTheme(color=$color, lineWidth=$lineWidth, lineDash=$lineDash)"
}
data class KLineAxisTheme(val textColor: String, val lineColor: String, val textSize: Double, val tickLength: Double) {
    init { validateColor(textColor, "Axis text"); validateColor(lineColor, "Axis line"); positive(textSize, "Axis text size"); nonNegative(tickLength, "Axis tick length") }
}
data class KLineCrosshairTheme(val lineColor: String, val lineWidth: Double, val labelBackgroundColor: String, val labelTextColor: String) {
    init { validateColor(lineColor, "Crosshair line"); validateColor(labelBackgroundColor, "Crosshair label background"); validateColor(labelTextColor, "Crosshair label text"); positive(lineWidth, "Crosshair line width") }
}
data class KLineTooltipTheme(val backgroundColor: String, val textColor: String, val titleColor: String, val textSize: Double, val cornerRadius: Double, val padding: Double) {
    init { validateColor(backgroundColor, "Tooltip background"); validateColor(textColor, "Tooltip text"); validateColor(titleColor, "Tooltip title"); positive(textSize, "Tooltip text size"); nonNegative(cornerRadius, "Tooltip corner radius"); nonNegative(padding, "Tooltip padding") }
}
class KLineIndicatorTheme(palette: List<String>, val lineWidth: Double, val textColor: String) {
    val palette = palette.toList()
    init { require(this.palette.isNotEmpty()) { "Indicator palette must not be empty" }; this.palette.forEach { validateColor(it, "Indicator palette") }; positive(lineWidth, "Indicator line width"); validateColor(textColor, "Indicator text") }
    override fun equals(other: Any?) = other is KLineIndicatorTheme && palette == other.palette && lineWidth == other.lineWidth && textColor == other.textColor
    override fun hashCode() = 31 * (31 * palette.hashCode() + lineWidth.hashCode()) + textColor.hashCode()
    override fun toString() = "KLineIndicatorTheme(palette=$palette, lineWidth=$lineWidth, textColor=$textColor)"
}
data class KLineOverlayTheme(val lineColor: String, val selectedColor: String, val pointColor: String, val lineWidth: Double, val pointRadius: Double) {
    init { validateColor(lineColor, "Overlay line"); validateColor(selectedColor, "Overlay selected"); validateColor(pointColor, "Overlay point"); positive(lineWidth, "Overlay line width"); positive(pointRadius, "Overlay point radius") }
}
data class KLineSeparatorTheme(val color: String, val width: Double) {
    init { validateColor(color, "Separator"); positive(width, "Separator width") }
}

data class KLineTheme(
    val backgroundColor: String,
    val direction: KLinePriceDirection,
    val candle: KLineCandleTheme,
    val grid: KLineGridTheme,
    val axis: KLineAxisTheme,
    val crosshair: KLineCrosshairTheme,
    val tooltip: KLineTooltipTheme,
    val indicator: KLineIndicatorTheme,
    val overlay: KLineOverlayTheme,
    val separator: KLineSeparatorTheme,
) {
    init { validateColor(backgroundColor, "Background") }
    companion object {
        val LIGHT: KLineTheme = KLineThemeOptions(baseDark = false).resolved()
        val DARK: KLineTheme = KLineThemeOptions(baseDark = true).resolved()
    }
}

class KLineCandleThemeOptions(var riseColor: String, var fallColor: String, var unchangedColor: String, var wickWidth: Double, var bodyWidthRatio: Double)
class KLineGridThemeOptions(var color: String, var lineWidth: Double, val lineDash: MutableList<Double>)
class KLineAxisThemeOptions(var textColor: String, var lineColor: String, var textSize: Double, var tickLength: Double)
class KLineCrosshairThemeOptions(var lineColor: String, var lineWidth: Double, var labelBackgroundColor: String, var labelTextColor: String)
class KLineTooltipThemeOptions(var backgroundColor: String, var textColor: String, var titleColor: String, var textSize: Double, var cornerRadius: Double, var padding: Double)
class KLineIndicatorThemeOptions(val palette: MutableList<String>, var lineWidth: Double, var textColor: String)
class KLineOverlayThemeOptions(var lineColor: String, var selectedColor: String, var pointColor: String, var lineWidth: Double, var pointRadius: Double)
class KLineSeparatorThemeOptions(var color: String, var width: Double)

class KLineThemeOptions internal constructor(baseDark: Boolean) {
    constructor() : this(false)

    var backgroundColor = if (baseDark) "#101318" else "#FFFFFF"
    var direction = KLinePriceDirection.RED_UP_GREEN_DOWN
    val candle = KLineCandleThemeOptions("#F04455", "#18A566", "#8A9099", 1.0, 0.72)
    val grid = KLineGridThemeOptions(if (baseDark) "#2A3039" else "#E8EBF0", 1.0, mutableListOf())
    val axis = KLineAxisThemeOptions(if (baseDark) "#A8AFBA" else "#596273", if (baseDark) "#3A414D" else "#D6DAE1", 11.0, 4.0)
    val crosshair = KLineCrosshairThemeOptions(if (baseDark) "#B4BBC6" else "#697386", 1.0, if (baseDark) "#E7EAF0" else "#333B48", if (baseDark) "#15191F" else "#FFFFFF")
    val tooltip = KLineTooltipThemeOptions(if (baseDark) "#252B34" else "#FFFFFF", if (baseDark) "#DCE1E8" else "#333B48", if (baseDark) "#FFFFFF" else "#111827", 12.0, 4.0, 8.0)
    val indicator = KLineIndicatorThemeOptions(mutableListOf("#2962FF", "#FF9800", "#9C27B0", "#00ACC1"), 1.0, if (baseDark) "#C9CFD8" else "#4B5563")
    val overlay = KLineOverlayThemeOptions("#2F80ED", "#F2C94C", "#2F80ED", 1.0, 4.0)
    val separator = KLineSeparatorThemeOptions(if (baseDark) "#343B46" else "#E1E5EB", 1.0)

    fun resolved(): KLineTheme {
        validateColor(backgroundColor, "Background")
        listOf(candle.riseColor, candle.fallColor, candle.unchangedColor).forEach { validateColor(it, "Candle") }
        listOf(grid.color, axis.textColor, axis.lineColor, crosshair.lineColor, crosshair.labelBackgroundColor,
            crosshair.labelTextColor, tooltip.backgroundColor, tooltip.textColor, tooltip.titleColor,
            indicator.textColor, overlay.lineColor, overlay.selectedColor, overlay.pointColor, separator.color).forEach { validateColor(it, "Theme") }
        indicator.palette.forEach { validateColor(it, "Indicator palette") }
        require(indicator.palette.isNotEmpty()) { "Indicator palette must not be empty" }
        positive(candle.wickWidth, "Candle wick width"); ratio(candle.bodyWidthRatio, "Candle body width ratio")
        positive(grid.lineWidth, "Grid line width"); grid.lineDash.forEach { positive(it, "Grid line dash") }
        positive(axis.textSize, "Axis text size"); nonNegative(axis.tickLength, "Axis tick length")
        positive(crosshair.lineWidth, "Crosshair line width")
        positive(tooltip.textSize, "Tooltip text size"); nonNegative(tooltip.cornerRadius, "Tooltip corner radius"); nonNegative(tooltip.padding, "Tooltip padding")
        positive(indicator.lineWidth, "Indicator line width")
        positive(overlay.lineWidth, "Overlay line width"); positive(overlay.pointRadius, "Overlay point radius")
        positive(separator.width, "Separator width")
        return KLineTheme(backgroundColor, direction,
            KLineCandleTheme(candle.riseColor, candle.fallColor, candle.unchangedColor, candle.wickWidth, candle.bodyWidthRatio),
            KLineGridTheme(grid.color, grid.lineWidth, grid.lineDash),
            KLineAxisTheme(axis.textColor, axis.lineColor, axis.textSize, axis.tickLength),
            KLineCrosshairTheme(crosshair.lineColor, crosshair.lineWidth, crosshair.labelBackgroundColor, crosshair.labelTextColor),
            KLineTooltipTheme(tooltip.backgroundColor, tooltip.textColor, tooltip.titleColor, tooltip.textSize, tooltip.cornerRadius, tooltip.padding),
            KLineIndicatorTheme(indicator.palette, indicator.lineWidth, indicator.textColor),
            KLineOverlayTheme(overlay.lineColor, overlay.selectedColor, overlay.pointColor, overlay.lineWidth, overlay.pointRadius),
            KLineSeparatorTheme(separator.color, separator.width))
    }

    companion object {
        fun light() = from(KLineTheme.LIGHT)
        fun dark() = from(KLineTheme.DARK)
        fun from(theme: KLineTheme) = KLineThemeOptions(theme == KLineTheme.DARK).apply {
            backgroundColor = theme.backgroundColor; direction = theme.direction
            candle.riseColor = theme.candle.riseColor; candle.fallColor = theme.candle.fallColor; candle.unchangedColor = theme.candle.unchangedColor; candle.wickWidth = theme.candle.wickWidth; candle.bodyWidthRatio = theme.candle.bodyWidthRatio
            grid.color = theme.grid.color; grid.lineWidth = theme.grid.lineWidth; grid.lineDash.clear(); grid.lineDash.addAll(theme.grid.lineDash)
            axis.textColor = theme.axis.textColor; axis.lineColor = theme.axis.lineColor; axis.textSize = theme.axis.textSize; axis.tickLength = theme.axis.tickLength
            crosshair.lineColor = theme.crosshair.lineColor; crosshair.lineWidth = theme.crosshair.lineWidth; crosshair.labelBackgroundColor = theme.crosshair.labelBackgroundColor; crosshair.labelTextColor = theme.crosshair.labelTextColor
            tooltip.backgroundColor = theme.tooltip.backgroundColor; tooltip.textColor = theme.tooltip.textColor; tooltip.titleColor = theme.tooltip.titleColor; tooltip.textSize = theme.tooltip.textSize; tooltip.cornerRadius = theme.tooltip.cornerRadius; tooltip.padding = theme.tooltip.padding
            indicator.palette.clear(); indicator.palette.addAll(theme.indicator.palette); indicator.lineWidth = theme.indicator.lineWidth; indicator.textColor = theme.indicator.textColor
            overlay.lineColor = theme.overlay.lineColor; overlay.selectedColor = theme.overlay.selectedColor; overlay.pointColor = theme.overlay.pointColor; overlay.lineWidth = theme.overlay.lineWidth; overlay.pointRadius = theme.overlay.pointRadius
            separator.color = theme.separator.color; separator.width = theme.separator.width
        }
    }
}

private fun validateColor(value: String, name: String) = require(value.isNotBlank()) { "$name color must not be blank" }
private fun positive(value: Double, name: String) = require(value.isFinite() && value > 0.0) { "$name must be finite and positive" }
private fun nonNegative(value: Double, name: String) = require(value.isFinite() && value >= 0.0) { "$name must be finite and non-negative" }
private fun ratio(value: Double, name: String) = require(value.isFinite() && value > 0.0 && value <= 1.0) { "$name must be within (0, 1]" }
