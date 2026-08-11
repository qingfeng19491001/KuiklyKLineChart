package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineBarRopeTracker
import com.tencent.kuiklybase.kline.data.PersistentKLineBarList

internal data class KLinePerformanceKey(
    val instanceId: String,
    val templateName: String,
)

internal data class KLinePerformanceMetrics(
    val oldBarGets: Long = 0,
    val newBarGets: Long = 0,
    val formulaWork: Long = 0,
    val ropeSplices: Long = 0,
    val ropeNodeVisits: Long = 0,
    val ropeLeafItemsCopied: Long = 0,
    val fullFallbacks: Long = 0,
    val fullCalculations: Long = 0,
    val schemaChecks: Long = 0,
    val storeMergeSteps: Long = 0,
    val storeChangeSteps: Long = 0,
    val barRopeSplices: Long = 0,
    val barRopeNodeVisits: Long = 0,
    val barLeafItemsCopied: Long = 0,
    val barOldItemReads: Long = 0,
    val barFastPathSteps: Long = 0,
    val publishComparisonSteps: Long = 0,
    val barIteratorItemGets: Long = 0,
    val barIteratorNodeVisits: Long = 0,
)

internal class KLinePerformanceTracker : KLineBarRopeTracker {
    private val values = mutableMapOf<KLinePerformanceKey, MutableMetrics>()

    fun reset() = values.clear()

    fun metrics(instanceId: String, templateName: String): KLinePerformanceMetrics =
        values[KLinePerformanceKey(instanceId, templateName)]?.snapshot() ?: KLinePerformanceMetrics()

    fun storeMetrics(): KLinePerformanceMetrics = metrics(STORE_KEY.instanceId, STORE_KEY.templateName)

    fun recordOldBarGet(key: KLinePerformanceKey) = mutate(key) { oldBarGets++; formulaWork++ }
    fun recordNewBarGet(key: KLinePerformanceKey) = mutate(key) { newBarGets++; formulaWork++ }
    fun recordRopeSplice(key: KLinePerformanceKey) = mutate(key) { ropeSplices++ }
    fun recordRopeNodeVisit(key: KLinePerformanceKey) = mutate(key) { ropeNodeVisits++ }
    fun recordRopeLeafItemsCopied(key: KLinePerformanceKey, count: Int) = mutate(key) { ropeLeafItemsCopied += count }
    fun recordFullFallback(key: KLinePerformanceKey) = mutate(key) { fullFallbacks++ }
    fun recordFullCalculation(key: KLinePerformanceKey) = mutate(key) { fullCalculations++ }
    fun recordSchemaChecks(key: KLinePerformanceKey, count: Int) = mutate(key) { schemaChecks += count }
    fun recordStoreMergeStep() = mutate(STORE_KEY) { storeMergeSteps++ }
    fun recordStoreChangeStep() = mutate(STORE_KEY) { storeChangeSteps++ }
    fun recordBarOldItemRead() = mutate(STORE_KEY) { barOldItemReads++ }
    fun recordBarFastPathStep() = mutate(STORE_KEY) { barFastPathSteps++ }
    fun recordPublishComparisonStep() = mutate(STORE_KEY) { publishComparisonSteps++ }
    override fun recordBarRopeSplice() = mutate(STORE_KEY) { barRopeSplices++ }
    override fun recordBarRopeNodeVisit() = mutate(STORE_KEY) { barRopeNodeVisits++ }
    override fun recordBarLeafItemsCopied(count: Int) = mutate(STORE_KEY) { barLeafItemsCopied += count }
    override fun recordBarIteratorItemGet() = mutate(STORE_KEY) { barIteratorItemGets++ }
    override fun recordBarIteratorNodeVisit() = mutate(STORE_KEY) { barIteratorNodeVisits++ }

    private inline fun mutate(key: KLinePerformanceKey, block: MutableMetrics.() -> Unit) {
        values.getOrPut(key, ::MutableMetrics).block()
    }

    private class MutableMetrics {
        var oldBarGets = 0L
        var newBarGets = 0L
        var formulaWork = 0L
        var ropeSplices = 0L
        var ropeNodeVisits = 0L
        var ropeLeafItemsCopied = 0L
        var fullFallbacks = 0L
        var fullCalculations = 0L
        var schemaChecks = 0L
        var storeMergeSteps = 0L
        var storeChangeSteps = 0L
        var barRopeSplices = 0L
        var barRopeNodeVisits = 0L
        var barLeafItemsCopied = 0L
        var barOldItemReads = 0L
        var barFastPathSteps = 0L
        var publishComparisonSteps = 0L
        var barIteratorItemGets = 0L
        var barIteratorNodeVisits = 0L
        fun snapshot() = KLinePerformanceMetrics(
            oldBarGets, newBarGets, formulaWork, ropeSplices, ropeNodeVisits, ropeLeafItemsCopied,
            fullFallbacks, fullCalculations, schemaChecks, storeMergeSteps, storeChangeSteps,
            barRopeSplices, barRopeNodeVisits, barLeafItemsCopied, barOldItemReads,
            barFastPathSteps, publishComparisonSteps, barIteratorItemGets, barIteratorNodeVisits,
        )
    }

    companion object {
        private val STORE_KEY = KLinePerformanceKey("__store__", "__store__")
    }
}

internal class TrackedKLineBars(
    private val source: List<KLineBar>,
    private val tracker: KLinePerformanceTracker,
    private val key: KLinePerformanceKey,
    private val old: Boolean,
) : AbstractList<KLineBar>() {
    override val size: Int get() = source.size
    override fun get(index: Int): KLineBar {
        recordGet()
        return source[index]
    }

    override fun iterator(): Iterator<KLineBar> = listIterator()

    override fun listIterator(index: Int): ListIterator<KLineBar> {
        val iterator = if (source is PersistentKLineBarList) {
            source.trackedListIterator(index, tracker)
        } else {
            source.listIterator(index)
        }
        return object : ListIterator<KLineBar> {
            override fun hasNext() = iterator.hasNext()
            override fun hasPrevious() = iterator.hasPrevious()
            override fun nextIndex() = iterator.nextIndex()
            override fun previousIndex() = iterator.previousIndex()
            override fun next(): KLineBar = iterator.next().also { recordGet() }
            override fun previous(): KLineBar = iterator.previous().also { recordGet() }
        }
    }

    private fun recordGet() {
        if (old) tracker.recordOldBarGet(key) else tracker.recordNewBarGet(key)
    }
}
