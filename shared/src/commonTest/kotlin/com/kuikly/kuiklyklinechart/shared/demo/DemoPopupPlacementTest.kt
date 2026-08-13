package com.kuikly.kuiklyklinechart.shared.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoPopupPlacementTest {
    @Test
    fun task2ShowcasesExposeChatCardAndSignalDetailInformation() {
        assertEquals(
            listOf("股票", "最新价", "涨跌幅", "周期", "AI摘要", "详情入口"),
            compactShowcaseFields(),
        )
        assertEquals(
            listOf("最新价", "涨跌幅", "最高", "最低", "今开", "成交量", "信号", "置信度", "关键位", "风险"),
            signalDetailFields(),
        )
    }

    @Test
    fun quoteHeaderUsesBrokerageFieldsAndKeepsTaskRequiredVolume() {
        assertEquals(
            listOf("高", "总值", "量比", "低", "流通", "换手", "开", "量", "额"),
            demoQuoteMetrics().map { it.label },
        )
    }

    @Test
    fun periodMenuIsAnchoredImmediatelyBelowItsLocalTabBar() {
        assertEquals(34f, demoPeriodMenuTop(34f))
    }

    @Test
    fun opensBelowWhenMenuFitsRemainingSpace() {
        val placement = demoPopupPlacement(
            anchorTop = 24f,
            anchorHeight = 18f,
            optionCount = 3,
            containerHeight = 240f,
        )

        assertFalse(placement.opensUpward)
        assertEquals(42f, placement.top)
    }

    @Test
    fun opensAboveWhenBottomPaneHasInsufficientSpace() {
        val placement = demoPopupPlacement(
            anchorTop = 260f,
            anchorHeight = 18f,
            optionCount = 5,
            containerHeight = 340f,
        )

        assertTrue(placement.opensUpward)
        assertEquals(115f, placement.top)
    }

    @Test
    fun upwardMenuNeverEscapesContainerTop() {
        val placement = demoPopupPlacement(
            anchorTop = 40f,
            anchorHeight = 18f,
            optionCount = 5,
            containerHeight = 80f,
        )

        assertTrue(placement.opensUpward)
        assertEquals(0f, placement.top)
    }
}
