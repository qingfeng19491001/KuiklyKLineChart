package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KLineBuiltInIncrementalAccessTest {
    @Test
    fun allBuiltInsDeclareTheExactTailUpdateOutputRange() {
        val bars = List(10) { bar(it.toLong()) }
        KLineBuiltInIndicators.templates.forEach { template ->
            val previous = template.calculate(bars, template.defaultParams)
            val context = KLineIndicatorUpdateContext(
                bars,
                bars.dropLast(1) + bar(9).copy(close = 99.0),
                previous,
                KLineDataChange(KLineDataChangeKind.TAIL_UPDATE, 9 until 10, 9 until 10, 9, 0),
            )

            assertEquals(9 until 10, template.outputAffectedRange(context), template.name)
        }
    }
    @Test
    fun allBuiltInsAccessOnlyTheTailAndRequiredSmallWindowAtOneHundredThousandBars() {
        val old = List(100_000) { index -> bar(index.toLong()) }
        KLineBuiltInIndicators.templates.forEach { template ->
            val previous = template.calculate(old, template.defaultParams)
            val appendedBars = CountingBars(old + bar(100_000))
            val oldBars = CountingBars(old)
            val appended = template.calculateIncremental(
                KLineIndicatorUpdateContext(
                    oldBars, appendedBars, previous,
                    KLineDataChange(KLineDataChangeKind.TAIL_APPEND, 100_000 until 100_000, 100_000 until 100_001, 100_000, 0),
                ),
                template.defaultParams,
            )!!
            assertEquals(100_001, appended.figures.firstOrNull()?.values?.size ?: 100_001, template.name)
            assertBounded(template.name, "append", oldBars.accessCount + appendedBars.accessCount)

            val replacement = old.dropLast(1) + bar(99_999).copy(open = 41.0, high = 43.0, low = 40.0, close = 42.0)
            val replacementBars = CountingBars(replacement)
            oldBars.accessCount = 0
            val replaced = template.calculateIncremental(
                KLineIndicatorUpdateContext(
                    oldBars, replacementBars, previous,
                    KLineDataChange(KLineDataChangeKind.TAIL_UPDATE, 99_999 until 100_000, 99_999 until 100_000, 99_999, 0),
                ),
                template.defaultParams,
            )!!
            assertEquals(100_000, replaced.figures.firstOrNull()?.values?.size ?: 100_000, template.name)
            assertBounded(template.name, "replace", oldBars.accessCount + replacementBars.accessCount)

            val lookback = (template as KLineIncrementalIndicatorTemplate).finiteLookback(template.defaultParams)
            if (lookback != null) {
                val prependedValues = listOf(bar(-3), bar(-2), bar(-1)) + old
                val prependedBars = CountingBars(prependedValues)
                oldBars.accessCount = 0
                val prepended = template.calculateIncremental(
                    KLineIndicatorUpdateContext(
                        oldBars, prependedBars, previous,
                        KLineDataChange(KLineDataChangeKind.HEAD_PREPEND, 0 until 0, 0 until 3, 0, 100_000),
                    ),
                    template.defaultParams,
                )!!
                assertEquals(100_003, prepended.figures.firstOrNull()?.values?.size ?: 100_003, template.name)
                assertBounded(template.name, "prepend", oldBars.accessCount + prependedBars.accessCount)
            }
        }
    }

    private fun assertBounded(name: String, operation: String, accesses: Int) {
        val expected = mapOf(
            "MA append" to 120, "MA replace" to 120, "MA prepend" to 32,
            "BOLL append" to 20, "BOLL replace" to 20, "BOLL prepend" to 22,
            "EXPMA append" to 2, "EXPMA replace" to 2,
            "BBI append" to 96, "BBI replace" to 96, "BBI prepend" to 26,
            "ENE append" to 10, "ENE replace" to 10, "ENE prepend" to 12,
            "VOL append" to 10, "VOL replace" to 10, "VOL prepend" to 12,
            "AMOUNT append" to 1, "AMOUNT replace" to 1, "AMOUNT prepend" to 3,
            "MACD append" to 1, "MACD replace" to 1,
            "KDJ append" to 19, "KDJ replace" to 19,
            "RSI append" to 6, "RSI replace" to 6,
            "WR append" to 21, "WR replace" to 21, "WR prepend" to 12,
            "BBD append" to 6, "BBD replace" to 6,
        )["$name $operation"]
        if (expected != null) {
            assertEquals(expected, accesses, "$name $operation access count")
        }
        assertTrue(accesses <= 512, "$name $operation accessed $accesses bars")
    }

    private fun bar(timestamp: Long): KLineBar {
        val close = 20.0 + timestamp % 97 / 7.0
        return KLineBar(timestamp, close - 0.5, close + 1.0, close - 1.0, close, timestamp % 101 + 1.0, close * (timestamp % 101 + 1.0))
    }

    private class CountingBars(private val values: List<KLineBar>) : AbstractList<KLineBar>() {
        var accessCount = 0
        override val size: Int get() = values.size
        override fun get(index: Int): KLineBar {
            accessCount++
            return values[index]
        }
    }
}
