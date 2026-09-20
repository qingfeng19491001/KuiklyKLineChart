package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.PersistentKLineBarList
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorTemplate
import com.tencent.kuiklybase.kline.indicator.KLinePerformanceMetrics
import com.tencent.kuiklybase.kline.indicator.KLinePerformanceTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KLineStoreBuiltInPerformanceTest {
    @Test
    fun storeEngineBuiltInsAndRopeStayIncrementalAtOneThousandBars() = verifySize(1_000)

    @Test
    fun storeEngineBuiltInsAndRopeStayIncrementalAtTenThousandBars() = verifySize(10_000)

    @Test
    fun storeEngineBuiltInsAndRopeStayIncrementalAtOneHundredThousandBars() = verifySize(100_000)

    @Test
    fun overlappingInputUsesOneLinearMergeWhilePersistentFastPathsStayBalanced() {
        val tracker = KLinePerformanceTracker()
        val store = KLineStore()
        store.replaceAll(List(100_000) { bar(it.toLong()) })
        store.trackPerformance(tracker)

        tracker.reset()
        store.append(List(20) { bar(99_990L + it) }, false)
        val overlap = tracker.storeMetrics()
        assertTrue(overlap.storeMergeSteps in 100_000..100_020)
        assertTrue(overlap.barOldItemReads in 100_000..100_020)
        assertTrue(overlap.barLeafItemsCopied in 100_000..100_040)

        tracker.reset()
        repeat(1_024) { offset -> store.applyRealtime(bar(100_010L + offset)) }
        val persistent = store.snapshot.bars as PersistentKLineBarList
        assertTrue(persistent.height <= 32, "height=${persistent.height}")
        assertEquals(0, tracker.storeMetrics().storeMergeSteps)
        assertEquals(0, tracker.storeMetrics().barOldItemReads)
        assertTrue(tracker.storeMetrics().barRopeNodeVisits <= 64L * 1_024)
        assertEquals(0L, persistent[0].timestamp)
        assertEquals(101_033L, persistent.last().timestamp)
        assertEquals((0L..101_033L).toList(), persistent.map(KLineBar::timestamp))

        tracker.reset()
        assertEquals(50_000, persistent.exactTimestampIndex(50_000, tracker))
        assertTrue(tracker.storeMetrics().barRopeNodeVisits <= 32)
        tracker.reset()
        assertEquals(persistent.lastIndex, persistent.nearestTimestampIndex(Long.MAX_VALUE, tracker))
        assertTrue(tracker.storeMetrics().barRopeNodeVisits <= 64)
    }

    private fun verifySize(size: Int) {
        val tracker = KLinePerformanceTracker()
        val store = KLineStore()
        store.replaceAll(List(size) { index -> bar(index.toLong()) })
        KLineBuiltInIndicators.templates.forEach { template ->
            store.setIndicator(KLineIndicatorInstance(template.name, template.name, "price", performanceParams(template.name), 8))
        }
        store.trackPerformance(tracker)
        assertTrue(store.snapshot.bars is PersistentKLineBarList)

        tracker.reset()
        store.append(listOf(bar(size.toLong())), false)
        assertTailMetrics(store, tracker, size, "append")
        assertResultLengths(store)

        tracker.reset()
        val replaced = bar(size.toLong()).copy(open = 51.0, high = 54.0, low = 49.0, close = 52.0)
        store.applyRealtime(replaced)
        assertTailMetrics(store, tracker, size, "replace")
        assertResultLengths(store)

        tracker.reset()
        store.prepend(listOf(bar(-3), bar(-2), bar(-1)), false)
        val storeMetrics = tracker.storeMetrics()
        assertEquals(0, storeMetrics.storeMergeSteps, "prepend merge size=$size")
        assertEquals(1, storeMetrics.storeChangeSteps, "prepend change size=$size")
        assertBarFastPathBounds(storeMetrics, size, "prepend")
        KLineBuiltInIndicators.templates.forEach { template ->
            val metrics = tracker.metrics(template.name, template.name)
            if (template.name in FINITE_NAMES) {
                assertIncrementalBounds(template, metrics, size, "prepend")
            } else {
                assertEquals(1, metrics.fullCalculations, "${template.name} prepend full size=$size")
            }
            assertEquals(0, metrics.fullFallbacks, "${template.name} prepend fallback size=$size")
        }
        assertFreshResults(store, if (size == 100_000) FINITE_NAMES else KLineBuiltInIndicators.templates.mapTo(mutableSetOf()) { it.name })
    }

    private fun assertTailMetrics(store: KLineStore, tracker: KLinePerformanceTracker, size: Int, operation: String) {
        val storeMetrics = tracker.storeMetrics()
        assertEquals(0, storeMetrics.storeMergeSteps, "$operation merge size=$size")
        assertEquals(1, storeMetrics.storeChangeSteps, "$operation change size=$size")
        assertBarFastPathBounds(storeMetrics, size, operation)
        KLineBuiltInIndicators.templates.forEach { template ->
            val metrics = tracker.metrics(template.name, template.name)
            assertIncrementalBounds(template, metrics, size, operation)
            assertEquals(0, metrics.fullCalculations, "${template.name} $operation full size=$size")
            assertEquals(0, metrics.fullFallbacks, "${template.name} $operation fallback size=$size")
        }
        assertTrue(store.snapshot.indicatorRevision > 0)
    }

    private fun assertIncrementalBounds(
        template: KLineIndicatorTemplate,
        metrics: KLinePerformanceMetrics,
        size: Int,
        operation: String,
    ) {
        assertTrue(metrics.formulaWork in 1..512, "${template.name} $operation formula=${metrics.formulaWork} size=$size")
        assertTrue(metrics.oldBarGets + metrics.newBarGets <= 512, "${template.name} $operation bars size=$size")
        assertTrue(metrics.ropeSplices in 1..16, "${template.name} $operation splices=${metrics.ropeSplices} size=$size")
        assertTrue(metrics.ropeNodeVisits <= 256, "${template.name} $operation nodes=${metrics.ropeNodeVisits} size=$size")
        assertTrue(metrics.ropeLeafItemsCopied <= 512, "${template.name} $operation leaves=${metrics.ropeLeafItemsCopied} size=$size")
        assertTrue(metrics.schemaChecks > 0, "${template.name} schema checks size=$size")
    }

    private fun assertBarFastPathBounds(metrics: KLinePerformanceMetrics, size: Int, operation: String) {
        assertEquals(1, metrics.barFastPathSteps, "$operation fast path size=$size")
        assertEquals(1, metrics.barRopeSplices, "$operation bar splice size=$size")
        assertEquals(0, metrics.barOldItemReads, "$operation old item reads size=$size")
        assertTrue(metrics.barRopeNodeVisits <= 128, "$operation bar nodes=${metrics.barRopeNodeVisits} size=$size")
        assertTrue(metrics.barLeafItemsCopied <= 128, "$operation bar copies=${metrics.barLeafItemsCopied} size=$size")
        assertTrue(metrics.barIteratorItemGets <= 512, "$operation iterator gets=${metrics.barIteratorItemGets} size=$size")
        assertTrue(
            metrics.barIteratorNodeVisits <= 256 + metrics.barIteratorItemGets * 2,
            "$operation iterator nodes=${metrics.barIteratorNodeVisits} gets=${metrics.barIteratorItemGets} size=$size",
        )
        assertTrue(metrics.publishComparisonSteps <= 1, "$operation publish comparisons size=$size")
    }

    private fun assertFreshResults(store: KLineStore, names: Set<String>) {
        KLineBuiltInIndicators.templates.filter { it.name in names }.forEach { template ->
            val params = store.snapshot.indicatorInstances.first { it.id == template.name }.params
            assertEquals(
                template.calculate(store.snapshot.bars, params),
                store.snapshot.indicatorResults.getValue(template.name),
                template.name,
            )
        }
    }

    private fun assertResultLengths(store: KLineStore) {
        store.snapshot.indicatorResults.values.forEach { result ->
            result.figures.forEach { figure -> assertEquals(store.snapshot.bars.size, figure.values.size) }
        }
    }

    private fun bar(timestamp: Long): KLineBar {
        val close = 20.0 + (timestamp * 37).mod(997) / 29.0
        return KLineBar(timestamp, close - 0.5, close + 1.0, close - 1.0, close, timestamp.mod(101) + 1.0, close * (timestamp.mod(101) + 1.0))
    }

    private fun performanceParams(name: String): List<Double> = when (name) {
        "MA", "EXPMA", "VOL", "RSI", "WR", "SMA", "EMA", "CCI", "DMI", "BIAS", "ROC", "BRAR" -> listOf(1.0)
        "BOLL" -> listOf(1.0, 2.0)
        "BBI" -> listOf(1.0, 1.0, 1.0, 1.0)
        "ENE" -> listOf(1.0, 11.0, 9.0)
        "MACD" -> listOf(1.0, 2.0, 1.0)
        "KDJ" -> listOf(1.0, 1.0, 1.0)
        "BBD" -> listOf(1.0, 1.0)
        "SAR" -> listOf(2.0, 2.0, 20.0)
        "CR" -> listOf(1.0, 1.0, 1.0, 1.0, 1.0)
        "EMV", "MTM", "PSY", "VR", "AO", "TRIX" -> listOf(1.0, 1.0)
        "DMA" -> listOf(1.0, 1.0, 1.0)
        else -> emptyList()
    }

    companion object {
        private val FINITE_NAMES = setOf(
            "MA", "BOLL", "BBI", "ENE", "VOL", "AMOUNT", "WR", "SMA", "CCI", "BIAS", "ROC",
            "BRAR", "CR", "DMA", "EMV", "MTM", "PSY", "VR", "AO",
        )
    }
}
