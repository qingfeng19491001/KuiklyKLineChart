package com.tencent.kuiklybase.kline.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KLineThemeTest {
    @Test
    fun resolvedThemeIsAValidatedDeepSnapshot() {
        val options = KLineThemeOptions().apply {
            candle.riseColor = "#112233"
            grid.lineDash += 4.0
            indicator.palette += "#abcdef"
            direction = KLinePriceDirection.GREEN_UP_RED_DOWN
        }
        val first = options.resolved()

        options.candle.riseColor = "#445566"
        options.grid.lineDash += 8.0
        options.indicator.palette[0] = "#000000"

        assertEquals("#112233", first.candle.riseColor)
        assertEquals(listOf(4.0), first.grid.lineDash)
        assertNotEquals(options.indicator.palette, first.indicator.palette)
        assertEquals(KLinePriceDirection.GREEN_UP_RED_DOWN, first.direction)
        assertFailsWith<IllegalArgumentException> {
            KLineThemeOptions().apply { axis.textSize = Double.NaN }.resolved()
        }
        assertFailsWith<IllegalArgumentException> {
            KLineThemeOptions().apply { overlay.lineColor = "" }.resolved()
        }
    }

    @Test
    fun lightAndDarkAreCompleteAndDistinctPresets() {
        assertEquals(KLineTheme.LIGHT, KLineThemeOptions.light().resolved())
        assertEquals(KLineTheme.DARK, KLineThemeOptions.dark().resolved())
        assertNotEquals(KLineTheme.LIGHT.backgroundColor, KLineTheme.DARK.backgroundColor)
        assertNotEquals(KLineTheme.LIGHT.tooltip.backgroundColor, KLineTheme.DARK.tooltip.backgroundColor)
    }

    @Test
    fun everyDslGroupIsConnectedToTheResolvedSnapshot() {
        val theme = KLineThemeOptions().apply {
            backgroundColor = "bg"
            candle.wickWidth = 2.0
            grid.lineWidth = 2.0
            axis.tickLength = 5.0
            crosshair.labelTextColor = "crosshair-text"
            tooltip.padding = 9.0
            indicator.lineWidth = 3.0
            overlay.pointRadius = 6.0
            separator.width = 4.0
        }.resolved()

        assertEquals("bg", theme.backgroundColor)
        assertEquals(2.0, theme.candle.wickWidth)
        assertEquals(2.0, theme.grid.lineWidth)
        assertEquals(5.0, theme.axis.tickLength)
        assertEquals("crosshair-text", theme.crosshair.labelTextColor)
        assertEquals(9.0, theme.tooltip.padding)
        assertEquals(3.0, theme.indicator.lineWidth)
        assertEquals(6.0, theme.overlay.pointRadius)
        assertEquals(4.0, theme.separator.width)
    }

    @Test
    fun immutableSnapshotCopyCannotBypassValidation() {
        assertFailsWith<IllegalArgumentException> { KLineTheme.LIGHT.copy(backgroundColor = "") }
        assertFailsWith<IllegalArgumentException> { KLineTheme.LIGHT.candle.copy(wickWidth = Double.NaN) }
    }
}
