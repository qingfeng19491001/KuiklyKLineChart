package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigure
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureResult
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorFigureType
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorResult
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorSeries
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorTemplate
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.indicator.KLineDataChangeKind
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorUpdateContext
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineMacdIndicator
import com.tencent.kuiklybase.kline.indicator.KLineIncrementalIndicatorTemplate
import com.tencent.kuiklybase.kline.indicator.KLinePerformanceTracker
import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.controller.KLineChartState
import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import kotlin.test.assertFailsWith
import kotlin.coroutines.cancellation.CancellationException
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KLineStoreTest {
    @Test
    fun equalScalarMutationDoesNotPublishOrCompareSnapshotCollections() {
        val tracker = KLinePerformanceTracker()
        val store = KLineStore()
        var notifications = 0
        store.observe { _, _ -> notifications++ }
        store.trackPerformance(tracker)

        store.setLoadPhase(KLineLoadDirection.INITIAL, KLineLoadPhase.IDLE)

        assertEquals(0, notifications)
        assertTrue(tracker.storeMetrics().publishComparisonSteps <= 1)
    }
    @Test
    fun initialBarsAreSortedAndDuplicateTimestampsUseTheLastValue() {
        val store = KLineStore()

        store.replaceAll(
            listOf(
                bar(timestamp = 3, close = 30.0),
                bar(timestamp = 1, close = 10.0),
                bar(timestamp = 3, close = 33.0),
                bar(timestamp = 2, close = 20.0),
            ),
        )

        assertEquals(listOf(1L, 2L, 3L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(33.0, store.snapshot.bars.last().close)
    }

    @Test
    fun equivalentDataDoesNotAdvanceDataOrIndicatorRevisionsWhileBoundaryFlagsStillPublish() {
        val store = KLineStore()
        store.setIndicator(KLineIndicatorInstance("ma", "MA", "price", listOf(1.0), 2))
        store.replaceAll(listOf(bar(2, 20.0), bar(1, 10.0)), hasMoreBefore = true)
        val dataRevision = store.snapshot.dataRevision
        val indicatorRevision = store.snapshot.indicatorRevision

        store.replaceAll(listOf(bar(1, 10.0), bar(2, 20.0)), hasMoreBefore = false)

        assertEquals(dataRevision, store.snapshot.dataRevision)
        assertEquals(indicatorRevision, store.snapshot.indicatorRevision)
        assertEquals(false, store.snapshot.hasMoreBefore)
    }

    @Test
    fun overlappingPagesUseLastIncomingValueWithoutAdvancingForAnEquivalentMerge() {
        val store = KLineStore()
        store.replaceAll(listOf(bar(Long.MIN_VALUE, 1.0), bar(0, 2.0), bar(Long.MAX_VALUE, 3.0)))
        val revision = store.snapshot.dataRevision

        store.append(listOf(bar(Long.MAX_VALUE, 30.0), bar(0, 20.0), bar(0, 2.0)), hasMoreAfter = true)
        assertEquals(listOf(1.0, 2.0, 30.0), store.snapshot.bars.map(KLineBar::close))
        assertEquals(revision + 1, store.snapshot.dataRevision)

        val changed = store.snapshot.dataRevision
        store.append(listOf(bar(Long.MAX_VALUE, 30.0)), hasMoreAfter = false)
        assertEquals(changed, store.snapshot.dataRevision)
        assertEquals(false, store.snapshot.hasMoreAfter)
    }

    @Test
    fun rangeUpdatePublishesDerivedMaValuesOutsideTheInputRangeAndCacheHitsStayFresh() {
        val store = KLineStore()
        val first = KLineIndicatorInstance("ma-first", "MA", "price", listOf(2.0), 2)
        val cached = KLineIndicatorInstance("ma-cached", "MA", "price", listOf(2.0), 2)
        store.replaceAll(listOf(bar(0, 1.0), bar(1, 2.0), bar(2, 3.0)))
        store.setIndicator(first)
        store.setIndicator(cached)
        val revision = store.snapshot.indicatorRevision
        assertEquals(1.5, store.snapshot.indicatorResults.getValue(first.id).figures.single().values[1])

        store.append(listOf(bar(0, 11.0)), false)

        assertEquals(revision + 1, store.snapshot.indicatorRevision)
        assertEquals(6.5, store.snapshot.indicatorResults.getValue(first.id).figures.single().values[1])
        assertEquals(6.5, store.snapshot.indicatorResults.getValue(cached.id).figures.single().values[1])
        assertEquals(6.5, store.snapshot.indicatorResults.getValue(cached.id).figures.single().values[1])
    }

    @Test
    fun rangeUpdateComparesCustomIncrementalOutputBeyondTheChangedInputRange() {
        val template = object : KLineIndicatorTemplate {
            override val name = "RANGE_DERIVED"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure("sum", "sum", KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
                var sum = 0.0
                val values = bars.mapIndexed { index, bar ->
                    sum += bar.close
                    if (index == 0) null else sum
                }
                return KLineIndicatorResult(name, series, listOf(KLineIndicatorFigureResult("sum", KLineIndicatorFigureType.LINE, values)))
            }
            override fun calculateIncremental(
                context: KLineIndicatorUpdateContext,
                params: List<Double>,
            ): KLineIndicatorResult = calculate(context.newBars, params)
        }
        val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
        store.replaceAll(listOf(bar(0, 1.0), bar(1, 2.0), bar(2, 3.0)))
        store.setIndicator(KLineIndicatorInstance("derived", template.name, "price", emptyList(), 2))
        val revision = store.snapshot.indicatorRevision

        store.append(listOf(bar(0, 11.0)), false)

        assertEquals(revision + 1, store.snapshot.indicatorRevision)
        assertEquals(listOf(null, 13.0, 16.0), store.snapshot.indicatorResults.getValue("derived").figures.single().values)
    }

    @Test
    fun undeclaredTailOutputRangeUsesFullEqualityForGlobalDerivedValuesAndCacheHits() {
        val template = object : KLineIncrementalIndicatorTemplate {
            override val name = "GLOBAL_TAIL_DERIVED"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure("ratio", "ratio", KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
                val last = bars.lastOrNull()?.close ?: 1.0
                return KLineIndicatorResult(
                    name,
                    series,
                    listOf(KLineIndicatorFigureResult("ratio", KLineIndicatorFigureType.LINE, bars.map { it.close / last })),
                )
            }
            override fun calculateIncremental(
                context: KLineIndicatorUpdateContext,
                params: List<Double>,
            ): KLineIndicatorResult = calculate(context.newBars, params)
        }
        val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
        val first = KLineIndicatorInstance("global-first", template.name, "price", emptyList(), 4)
        val cached = KLineIndicatorInstance("global-cached", template.name, "price", emptyList(), 4)
        store.replaceAll(listOf(bar(0, 1.0), bar(1, 2.0), bar(2, 3.0)))
        store.setIndicator(first)
        store.setIndicator(cached)
        val revision = store.snapshot.indicatorRevision

        store.applyRealtime(bar(2, 30.0))

        assertEquals(revision + 1, store.snapshot.indicatorRevision)
        assertEquals(1.0 / 30.0, store.snapshot.indicatorResults.getValue(first.id).figures.single().values[0])
        assertEquals(2.0 / 30.0, store.snapshot.indicatorResults.getValue(cached.id).figures.single().values[1])
    }

    @Test
    fun emptyOrOutOfBoundsOutputRangesFallBackToFullEquality() {
        listOf(1..0, -1..0, 2..3).forEachIndexed { templateIndex, declaredRange ->
            val template = object : KLineIndicatorTemplate {
                override val name = "INVALID_OUTPUT_RANGE_$templateIndex"
                override val defaultParams = emptyList<Double>()
                override val series = KLineIndicatorSeries.PRICE
                override val figures = listOf(KLineIndicatorFigure("ratio", "ratio", KLineIndicatorFigureType.LINE))
                override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
                    val last = bars.lastOrNull()?.close ?: 1.0
                    return KLineIndicatorResult(
                        name,
                        series,
                        listOf(KLineIndicatorFigureResult("ratio", KLineIndicatorFigureType.LINE, bars.map { it.close / last })),
                    )
                }
                override fun calculateIncremental(
                    context: KLineIndicatorUpdateContext,
                    params: List<Double>,
                ): KLineIndicatorResult = calculate(context.newBars, params)
                override fun outputAffectedRange(context: KLineIndicatorUpdateContext): IntRange = declaredRange
            }
            val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
            store.replaceAll(listOf(bar(0, 1.0), bar(1, 2.0), bar(2, 3.0)))
            store.setIndicator(KLineIndicatorInstance("invalid", template.name, "price", emptyList(), 4))
            val revision = store.snapshot.indicatorRevision

            store.applyRealtime(bar(2, 30.0))

            assertEquals(revision + 1, store.snapshot.indicatorRevision, "range=$declaredRange")
            assertEquals(1.0 / 30.0, store.snapshot.indicatorResults.getValue("invalid").figures.single().values[0])
        }
    }

    @Test
    fun sameNameTemplateReplacementPublishesChangedFigureSchemaOnTailUpdate() {
        fun template(figureKey: String) = object : KLineIndicatorTemplate {
            override val name = "REPLACEABLE_SCHEMA"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure(figureKey, figureKey, KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>) = KLineIndicatorResult(
                name,
                series,
                listOf(KLineIndicatorFigureResult(figureKey, KLineIndicatorFigureType.LINE, bars.map { it.close })),
            )
            override fun calculateIncremental(
                context: KLineIndicatorUpdateContext,
                params: List<Double>,
            ): KLineIndicatorResult = calculate(context.newBars, params)
            override fun outputAffectedRange(context: KLineIndicatorUpdateContext) = context.change.newAffectedRange
        }
        val original = template("old")
        val registry = KLineExtensionRegistry(listOf(original))
        val store = KLineStore(extensionRegistry = registry)
        store.replaceAll(listOf(bar(0, 1.0), bar(1, 2.0)))
        store.setIndicator(KLineIndicatorInstance("replaceable", original.name, "price", emptyList(), 2))

        registry.register(template("new"))
        store.applyRealtime(bar(1, 2.0).copy(volume = 99.0))

        assertEquals("new", store.snapshot.indicatorResults.getValue("replaceable").figures.single().key)
    }

    @Test
    fun cacheHitOutputRangeFailureFallsBackWithoutInterruptingOtherIndicators() {
        var outputRangeCalls = 0
        var outputRangeFailure: Exception = IllegalStateException("broken optimization hint")
        val throwingHint = object : KLineIndicatorTemplate {
            override val name = "THROWING_HINT"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure("v", "v", KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>) = KLineIndicatorResult(
                name, series, listOf(KLineIndicatorFigureResult("v", KLineIndicatorFigureType.LINE, bars.map { it.close })),
            )
            override fun calculateIncremental(
                context: KLineIndicatorUpdateContext,
                params: List<Double>,
            ): KLineIndicatorResult = calculate(context.newBars, params)
            override fun outputAffectedRange(context: KLineIndicatorUpdateContext): IntRange {
                outputRangeCalls++
                if (outputRangeCalls == 2) throw outputRangeFailure
                return context.change.newAffectedRange
            }
        }
        val other = object : KLineIndicatorTemplate {
            override val name = "AFTER_THROWING_HINT"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure("v", "v", KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>) = KLineIndicatorResult(
                name, series, listOf(KLineIndicatorFigureResult("v", KLineIndicatorFigureType.LINE, bars.map { it.close * 2 })),
            )
        }
        val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(throwingHint, other)))
        store.replaceAll(listOf(bar(0, 1.0), bar(1, 2.0)))
        store.setIndicator(KLineIndicatorInstance("first", throwingHint.name, "price", emptyList(), 2))
        store.setIndicator(KLineIndicatorInstance("cached", throwingHint.name, "price", emptyList(), 2))
        store.setIndicator(KLineIndicatorInstance("other", other.name, "price", emptyList(), 2))

        store.applyRealtime(bar(1, 30.0))

        assertEquals(30.0, store.snapshot.indicatorResults.getValue("cached").figures.single().values.last())
        assertEquals(60.0, store.snapshot.indicatorResults.getValue("other").figures.single().values.last())

        outputRangeCalls = 0
        outputRangeFailure = CancellationException("cancelled optimization hint")
        assertFailsWith<CancellationException> { store.applyRealtime(bar(1, 40.0)) }
    }

    @Test
    fun dataChangeClassifiesOverlapAndInsertRangesPrecisely() {
        fun configured(): Pair<KLineStore, IncrementalRecordingTemplate> {
            val template = IncrementalRecordingTemplate()
            val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
            store.setIndicator(KLineIndicatorInstance("value", template.name, "price", emptyList(), 2))
            store.replaceAll((0L..4L).map { bar(it, it.toDouble()) })
            return store to template
        }

        val (appendStore, appendTemplate) = configured()
        appendStore.append(listOf(bar(2, 20.0), bar(5, 5.0)), false)
        assertEquals(KLineDataChangeKind.RANGE_UPDATE, appendTemplate.lastContext?.change?.kind)
        assertEquals(2 until 5, appendTemplate.lastContext?.change?.oldAffectedRange)
        assertEquals(2 until 6, appendTemplate.lastContext?.change?.newAffectedRange)

        val (prependStore, prependTemplate) = configured()
        prependStore.prepend(listOf(bar(-1, -1.0), bar(2, 20.0)), false)
        assertEquals(KLineDataChangeKind.RANGE_UPDATE, prependTemplate.lastContext?.change?.kind)
        assertEquals(0 until 3, prependTemplate.lastContext?.change?.oldAffectedRange)
        assertEquals(0 until 4, prependTemplate.lastContext?.change?.newAffectedRange)
        assertEquals(2, prependTemplate.lastContext?.change?.unchangedSuffixCount)

        val (tailStore, tailTemplate) = configured()
        tailStore.applyRealtime(bar(4, 40.0))
        assertEquals(KLineDataChangeKind.TAIL_UPDATE, tailTemplate.lastContext?.change?.kind)
        assertEquals(4 until 5, tailTemplate.lastContext?.change?.newAffectedRange)
    }

    @Test
    fun emptyStorePrependAndAppendPreserveCallerDirectionAndEmptyInputDoesNotRecalculate() {
        fun configured(): Pair<KLineStore, IncrementalRecordingTemplate> {
            val template = IncrementalRecordingTemplate()
            val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
            store.setIndicator(KLineIndicatorInstance("value", template.name, "price", emptyList(), 2))
            return store to template
        }

        val (prependStore, prependTemplate) = configured()
        prependStore.prepend(listOf(bar(1, 1.0), bar(2, 2.0)), false)
        val prepend = prependTemplate.lastContext!!.change
        assertEquals(KLineDataChangeKind.HEAD_PREPEND, prepend.kind)
        assertEquals(0 until 0, prepend.oldAffectedRange)
        assertEquals(0 until 2, prepend.newAffectedRange)
        assertEquals(0, prepend.unchangedPrefixCount)
        assertEquals(0, prepend.unchangedSuffixCount)
        val previousContext = prependTemplate.lastContext
        val revision = prependStore.snapshot.dataRevision
        prependStore.prepend(emptyList(), true)
        assertSame(previousContext, prependTemplate.lastContext)
        assertEquals(revision, prependStore.snapshot.dataRevision)
        assertEquals(true, prependStore.snapshot.hasMoreBefore)

        val (appendStore, appendTemplate) = configured()
        appendStore.append(listOf(bar(1, 1.0), bar(2, 2.0)), false)
        val append = appendTemplate.lastContext!!.change
        assertEquals(KLineDataChangeKind.TAIL_APPEND, append.kind)
        assertEquals(0 until 0, append.oldAffectedRange)
        assertEquals(0 until 2, append.newAffectedRange)
        assertEquals(0, append.unchangedPrefixCount)
        assertEquals(0, append.unchangedSuffixCount)
        val appendContext = appendTemplate.lastContext
        val appendRevision = appendStore.snapshot.dataRevision
        appendStore.append(emptyList(), true)
        assertSame(appendContext, appendTemplate.lastContext)
        assertEquals(appendRevision, appendStore.snapshot.dataRevision)
        assertEquals(true, appendStore.snapshot.hasMoreAfter)
    }

    @Test
    fun realtimeBarReplacesTailOrAppendsNewTail() {
        val store = KLineStore()
        store.replaceAll(listOf(bar(1, 10.0), bar(2, 20.0)))

        store.applyRealtime(bar(2, 22.0))
        store.applyRealtime(bar(3, 30.0))

        assertEquals(listOf(10.0, 22.0, 30.0), store.snapshot.bars.map(KLineBar::close))
    }

    @Test
    fun incrementalTemplateReceivesPreciseTailChangesAndProcessesOnlyTheAffectedRange() {
        val template = IncrementalRecordingTemplate()
        val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
        store.setIndicator(KLineIndicatorInstance("incremental", template.name, "price", emptyList(), 2))
        store.replaceAll((0L until 100_000L).map { bar(it, it.toDouble()) })

        store.append(listOf(bar(100_000, 100_000.0)), hasMoreAfter = true)
        assertEquals(KLineDataChangeKind.TAIL_APPEND, template.lastContext?.change?.kind)
        assertEquals(100_000 until 100_001, template.lastContext?.change?.newAffectedRange)
        assertEquals(1, template.processedBars)

        store.applyRealtime(bar(100_000, -1.0))
        assertEquals(KLineDataChangeKind.TAIL_UPDATE, template.lastContext?.change?.kind)
        assertEquals(1, template.processedBars)
        assertEquals(-1.0, store.snapshot.indicatorResults.getValue("incremental").figures.single().values.last())

        val unchangedResult = store.snapshot.indicatorResults.getValue("incremental")
        val unchangedRevision = store.snapshot.indicatorRevision
        store.applyRealtime(bar(100_000, -1.0).copy(volume = 1.0))
        assertSame(unchangedResult, store.snapshot.indicatorResults.getValue("incremental"))
        assertEquals(unchangedRevision, store.snapshot.indicatorRevision)
        assertEquals(1, template.processedBars)

        store.prepend((-10L until 0L).map { bar(it, it.toDouble()) }, hasMoreBefore = false)
        assertEquals(KLineDataChangeKind.HEAD_PREPEND, template.lastContext?.change?.kind)
        assertEquals(10, template.processedBars)

        repeat(128) { update -> store.applyRealtime(bar(100_000, update.toDouble())) }
        val values = store.snapshot.indicatorResults.getValue("incremental").figures.single().values
        assertEquals(-10.0, values.first())
        assertEquals(127.0, values.last())
        assertEquals(1, template.processedBars)

        store.replaceAll((0L until 1_000L).map { bar(it, it.toDouble()) })
        repeat(1_024) { update -> store.applyRealtime(bar(999, update.toDouble())) }
        val repeatedlyUpdated = store.snapshot.indicatorResults.getValue("incremental").figures.single().values
        assertEquals(0.0, repeatedlyUpdated.first())
        assertEquals(1_023.0, repeatedlyUpdated.last())
        assertEquals(1, template.processedBars)
    }

    @Test
    fun incrementalCalculationWorkStaysBoundedAtOneTenAndHundredThousandBars() {
        listOf(1_000, 10_000, 100_000).forEach { size ->
            val template = IncrementalRecordingTemplate()
            val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
            store.setIndicator(KLineIndicatorInstance("value", template.name, "price", emptyList(), 2))
            store.replaceAll((0 until size).map { bar(it.toLong(), it.toDouble()) })
            store.append(listOf(bar(size.toLong(), size.toDouble())), false)
            assertEquals(1, template.processedBars, "append size=$size")
            store.applyRealtime(bar(size.toLong(), -1.0))
            assertEquals(1, template.processedBars, "realtime size=$size")
            store.prepend(listOf(bar(-3, -3.0), bar(-2, -2.0), bar(-1, -1.0)), false)
            assertEquals(3, template.processedBars, "prepend size=$size")
            val values = store.snapshot.indicatorResults.getValue("value").figures.single().values
            assertEquals(-3.0, values.first())
            assertEquals(-1.0, values.last())
        }
    }

    @Test
    fun incrementalFailureOrInvalidShapeFallsBackToValidatedFullCalculation() {
        val errors = mutableListOf<KLineError>()
        val template = FallbackTemplate()
        val store = KLineStore(errors::add, KLineExtensionRegistry(listOf(template)))
        store.setIndicator(KLineIndicatorInstance("fallback", template.name, "price", emptyList(), 2))
        store.replaceAll(listOf(bar(1, 1.0)))
        val fullBefore = template.fullCount

        template.incrementalMode = 1
        store.append(listOf(bar(2, 2.0)), false)
        assertEquals(fullBefore + 1, template.fullCount)
        assertEquals(emptyList(), errors)
        assertEquals(listOf(1.0, 2.0), store.snapshot.indicatorResults.getValue("fallback").figures.single().values)

        template.incrementalMode = 2
        store.applyRealtime(bar(2, 3.0))
        assertEquals(fullBefore + 2, template.fullCount)
        assertEquals(emptyList(), errors)
        assertEquals(listOf(1.0, 3.0), store.snapshot.indicatorResults.getValue("fallback").figures.single().values)

        val previousSnapshot = store.snapshot
        template.incrementalMode = 3
        store.applyRealtime(bar(2, 4.0))
        assertEquals(emptyMap(), store.snapshot.indicatorResults)
        assertEquals(listOf(1.0, 3.0), previousSnapshot.indicatorResults.getValue("fallback").figures.single().values)
        assertEquals(1, errors.count { it.code == KLineErrorCode.INDICATOR_CALCULATION_FAILED })
    }

    @Test
    fun malformedLegacyFullResultIsHiddenAndReportedWithoutAffectingOtherIndicators() {
        val errors = mutableListOf<KLineError>()
        val malformed = object : KLineIndicatorTemplate {
            override val name = "MALFORMED"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure("value", "value", KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>) = KLineIndicatorResult(
                name, series, listOf(KLineIndicatorFigureResult("wrong", KLineIndicatorFigureType.BAR, emptyList())),
            )
        }
        val good = IncrementalRecordingTemplate()
        val store = KLineStore(errors::add, KLineExtensionRegistry(listOf(malformed, good)))
        store.setIndicator(KLineIndicatorInstance("bad", malformed.name, "price", emptyList(), 2))
        store.setIndicator(KLineIndicatorInstance("good", good.name, "price", emptyList(), 2))
        store.replaceAll(listOf(bar(1, 1.0)))

        assertEquals(setOf("good"), store.snapshot.indicatorResults.keys)
        assertEquals(KLineErrorCode.INDICATOR_CALCULATION_FAILED, errors.last().code)
    }

    @Test
    fun everyBuiltInTailAppendAndRealtimeUpdateMatchesFreshFullCalculation() {
        val initial = (1L..40L).map { timestamp ->
            val close = timestamp.toDouble() + (timestamp % 3) * 0.25
            KLineBar(timestamp, close - 0.5, close + 1.0, close - 1.0, close, timestamp * 10.0, timestamp * close)
        }
        KLineBuiltInIndicators.templates.forEach { template ->
            val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
            val instance = KLineIndicatorInstance("value", template.name, "price", template.defaultParams, 6)
            store.setIndicator(instance)
            store.replaceAll(initial)
            val appended = KLineBar(41, 40.5, 42.0, 40.0, 41.0, 410.0, 16_810.0)
            store.append(listOf(appended), false)
            assertEquals(template.calculate(initial + appended, template.defaultParams), store.snapshot.indicatorResults.getValue("value"), template.name)

            val replacement = KLineBar(41, 42.0, 44.0, 41.0, 43.0, 430.0, 18_490.0)
            store.applyRealtime(replacement)
            assertEquals(template.calculate(initial + replacement, template.defaultParams), store.snapshot.indicatorResults.getValue("value"), template.name)

            val prepended = (-4L..0L).map { timestamp -> KLineBar(timestamp, 1.0, 3.0, 0.0, 2.0, 5.0, 10.0) }
            store.prepend(prepended, false)
            assertEquals(template.calculate(prepended + initial + replacement, template.defaultParams), store.snapshot.indicatorResults.getValue("value"), "${template.name} prepend")
        }
    }

    @Test
    fun builtInsMatchFreshFullAcrossHighEntropyNonDefaultIncrementalMatrix() {
        val paramsByName = mapOf(
            "MA" to listOf(3.0, 7.0), "BOLL" to listOf(5.0, 1.5), "EXPMA" to listOf(3.0, 8.0),
            "BBI" to listOf(2.0, 3.0, 5.0, 7.0), "ENE" to listOf(4.0, 7.0, 3.0), "VOL" to listOf(3.0, 6.0),
            "AMOUNT" to emptyList(), "MACD" to listOf(3.0, 8.0, 4.0), "KDJ" to listOf(5.0, 2.0, 4.0),
            "RSI" to listOf(3.0, 5.0), "WR" to listOf(4.0), "BBD" to listOf(3.0, 4.0),
        )
        val finite = setOf("MA", "BOLL", "BBI", "ENE", "VOL", "AMOUNT", "WR")
        KLineBuiltInIndicators.templates.forEach { template ->
            val params = paramsByName.getValue(template.name)
            val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
            store.setIndicator(KLineIndicatorInstance("value", template.name, "price", params, 8))
            assertIndicatorClose(template.calculate(emptyList(), params), store.snapshot.indicatorResults.getValue("value"), "${template.name} empty")
            var bars = (10L..12L).map(::entropyBar)
            store.replaceAll(bars)
            assertIndicatorClose(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} small")
            (13L..18L).forEach { timestamp ->
                val next = entropyBar(timestamp)
                bars = bars + next
                store.append(listOf(next), false)
                assertIndicatorClose(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} step $timestamp")
            }
            val batch = (19L..23L).map(::entropyBar)
            bars = bars + batch
            store.append(batch, false)
            assertIndicatorClose(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} batch")
            val replaced = entropyBar(23).copy(close = entropyBar(23).close + 1.25, high = entropyBar(23).high + 1.25)
            bars = bars.dropLast(1) + replaced
            store.applyRealtime(replaced)
            assertIndicatorClose(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} replace")
            if (template.name in finite) {
                val head = (5L..9L).map(::entropyBar)
                bars = head + bars
                store.prepend(head, false)
                assertIndicatorClose(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} prepend")
                val overlap = listOf(entropyBar(4), entropyBar(10).copy(close = 99.0, high = 100.0))
                bars = (bars + overlap).associateBy(KLineBar::timestamp).values.sortedBy(KLineBar::timestamp)
                store.prepend(overlap, false)
                assertIndicatorClose(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} overlap")
            }
        }
    }

    @Test
    fun finiteWindowBuiltInsAreBitExactAcrossLargeIncrementalChanges() {
        val paramsByName = mapOf(
            "MA" to listOf(7.0, 31.0), "BOLL" to listOf(23.0, 1.7),
            "BBI" to listOf(3.0, 11.0, 23.0, 47.0), "ENE" to listOf(29.0, 8.0, 5.0),
            "VOL" to listOf(13.0, 37.0), "AMOUNT" to emptyList(), "WR" to listOf(41.0),
        )
        KLineBuiltInIndicators.templates.filter { it.name in paramsByName }.forEach { template ->
            val params = paramsByName.getValue(template.name)
            val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
            store.setIndicator(KLineIndicatorInstance("value", template.name, "price", params, 8))
            var bars = (100L until 1_137L).map(::entropyBar)
            store.replaceAll(bars)
            val appended = entropyBar(1_137)
            bars = bars + appended
            store.append(listOf(appended), false)
            assertEquals(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} append")
            val batch = (1_138L..1_146L).map(::entropyBar)
            bars = bars + batch
            store.append(batch, false)
            assertEquals(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} batch")
            val tail = entropyBar(1_146).copy(open = 50.0, high = 55.0, low = 48.0, close = 52.0)
            bars = bars.dropLast(1) + tail
            store.applyRealtime(tail)
            assertEquals(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} replace")
            val head = (90L..99L).map(::entropyBar)
            bars = head + bars
            store.prepend(head, false)
            assertEquals(template.calculate(bars, params), store.snapshot.indicatorResults.getValue("value"), "${template.name} prepend")
        }
    }

    @Test
    fun macdIncrementalPathHandlesEqualFastAndSlowPeriods() {
        val bars = (1L..20L).map { bar(it, it.toDouble()) }
        val params = listOf(5.0, 5.0, 3.0)
        val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(KLineMacdIndicator)))
        store.setIndicator(KLineIndicatorInstance("macd", "MACD", "price", params, 4))
        store.replaceAll(bars)
        store.append(listOf(bar(21, 22.0)), false)

        assertEquals(KLineMacdIndicator.calculate(bars + bar(21, 22.0), params), store.snapshot.indicatorResults.getValue("macd"))
    }

    @Test
    fun macdStoredTailStateMatchesFreshFullExactlyAcrossBatchAndReplace() {
        val params = listOf(4.0, 9.0, 5.0)
        val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(KLineMacdIndicator)))
        store.setIndicator(KLineIndicatorInstance("macd", "MACD", "price", params, 8))
        var bars = (1L..37L).map(::entropyBar)
        store.replaceAll(bars)
        (38L..45L).forEach { timestamp ->
            val next = entropyBar(timestamp)
            bars = bars + next
            store.append(listOf(next), false)
            assertEquals(KLineMacdIndicator.calculate(bars, params), store.snapshot.indicatorResults.getValue("macd"), "step $timestamp")
        }
        val batch = (46L..53L).map(::entropyBar)
        bars = bars + batch
        store.append(batch, false)
        assertEquals(KLineMacdIndicator.calculate(bars, params), store.snapshot.indicatorResults.getValue("macd"), "batch")
        val replacement = entropyBar(53).copy(open = 76.0, close = 77.25, high = 78.0, low = 75.0)
        bars = bars.dropLast(1) + replacement
        store.applyRealtime(replacement)
        assertEquals(KLineMacdIndicator.calculate(bars, params), store.snapshot.indicatorResults.getValue("macd"), "replace")
    }

    @Test
    fun invalidBarsAreRejectedAndReportedWithoutDiscardingValidBars() {
        val errors = mutableListOf<KLineError>()
        val store = KLineStore(onError = errors::add)

        store.replaceAll(
            listOf(
                bar(1, 10.0),
                KLineBar(timestamp = 2, open = 12.0, high = 11.0, low = 9.0, close = 10.0),
                KLineBar(
                    timestamp = 3,
                    open = 10.0,
                    high = 11.0,
                    low = 9.0,
                    close = 10.0,
                    volume = -1.0,
                ),
            ),
        )

        assertEquals(listOf(1L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(2, errors.size)
        assertEquals(listOf(KLineErrorCode.INVALID_DATA, KLineErrorCode.INVALID_DATA), errors.map(KLineError::code))
    }

    @Test
    fun historicalPagesMergeInTimestampOrderAndUpdateBoundaries() {
        val store = KLineStore()
        store.replaceAll(
            bars = listOf(bar(3, 30.0), bar(4, 40.0)),
            hasMoreBefore = true,
            hasMoreAfter = true,
        )

        val insertedBefore = store.prepend(
            bars = listOf(bar(2, 20.0), bar(1, 10.0)),
            hasMoreBefore = false,
        )
        store.append(
            bars = listOf(bar(6, 60.0), bar(5, 50.0)),
            hasMoreAfter = false,
        )
        store.applyRealtime(bar(4, 404.0))

        assertEquals(2, insertedBefore)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(false, store.snapshot.hasMoreBefore)
        assertEquals(false, store.snapshot.hasMoreAfter)
        assertEquals(40.0, store.snapshot.bars.first { it.timestamp == 4L }.close)
    }

    @Test
    fun indicatorInstancesAndResultsStayInTheStoreAcrossDataAndConfigurationChanges() {
        val store = KLineStore()
        val ma = KLineIndicatorInstance(
            id = "price-ma",
            templateName = "MA",
            paneId = "price",
            params = listOf(3.0),
            precision = 2,
        )

        store.setIndicator(ma)
        store.replaceAll((1L..4L).map { bar(it, it.toDouble()) })

        assertEquals(listOf("price-ma"), store.snapshot.indicatorInstances.map { it.id })
        assertEquals(listOf(null, null, 2.0, 3.0), store.snapshot.indicatorResults.getValue("price-ma").figures.single().values)
        assertEquals(2L, store.snapshot.indicatorRevision)

        store.setIndicator(ma.copy(params = listOf(2.0)))
        assertEquals(listOf(null, 1.5, 2.5, 3.5), store.snapshot.indicatorResults.getValue("price-ma").figures.single().values)
        store.applyRealtime(bar(4, 5.0))
        assertEquals(listOf(null, 1.5, 2.5, 4.0), store.snapshot.indicatorResults.getValue("price-ma").figures.single().values)
        store.removeIndicator("price-ma")
        assertEquals(emptyList(), store.snapshot.indicatorInstances)
        assertEquals(emptyMap(), store.snapshot.indicatorResults)
    }

    @Test
    fun overlayMutationsPublishImmutableSnapshotsAndAdvanceOverlayRevision() {
        val store = KLineStore()
        val style = KLineOverlayFigureStyle(color = "#abcdef", lineWidth = 2.0)
        val first = KLineOverlayInstance(
            id = "overlay-1",
            templateName = "segment",
            groupId = "drawing-group",
            paneId = "price",
            points = listOf(KLineOverlayPoint(1, 10.0), KLineOverlayPoint(2, 20.0)),
            visible = true,
            locked = false,
            magnetMode = KLineOverlayMagnetMode.WEAK,
            zIndex = 4,
            styles = mapOf("default" to style),
            extendData = mapOf("source" to "user"),
        )
        val second = first.copy(id = "overlay-2")
        val emptySnapshot = store.snapshot

        store.addOverlay(first)
        val addedSnapshot = store.snapshot
        store.addOverlay(second)
        store.selectOverlay(first.id)
        store.updateOverlay(
            first.copy(visible = false, locked = true, magnetMode = KLineOverlayMagnetMode.STRONG, zIndex = 9),
        )

        assertEquals(emptyList(), emptySnapshot.overlayInstances)
        assertEquals(listOf(first), addedSnapshot.overlayInstances)
        assertEquals(4L, store.snapshot.overlayRevision)
        assertEquals(first.id, store.snapshot.selectedOverlayId)
        val updatedFirst = store.snapshot.overlayInstances.first { it.id == first.id }
        assertEquals(false, updatedFirst.visible)
        assertEquals(true, updatedFirst.locked)
        assertEquals(KLineOverlayMagnetMode.STRONG, updatedFirst.magnetMode)
        assertEquals(9, updatedFirst.zIndex)
        assertEquals(style, updatedFirst.styles.getValue("default"))
        assertEquals("user", updatedFirst.extendData.getValue("source"))

        store.removeOverlay(second.id)
        assertEquals(listOf(first.id), store.snapshot.overlayInstances.map { it.id })
        assertEquals(5L, store.snapshot.overlayRevision)
        store.removeOverlayGroup("drawing-group")
        assertEquals(emptyList(), store.snapshot.overlayInstances)
        assertEquals(null, store.snapshot.selectedOverlayId)
        assertEquals(6L, store.snapshot.overlayRevision)
        store.removeOverlay("missing")
        assertEquals(6L, store.snapshot.overlayRevision)
    }

    @Test
    fun overlaysUseStableAscendingZOrderAcrossCreationAndUpdates() {
        val store = KLineStore()
        val high = overlay("high", zIndex = 10, groupId = "ordered")
        val low = overlay("low", zIndex = -2, groupId = "ordered")
        val equalFirst = overlay("equal-first", zIndex = 5, groupId = "ordered")
        val equalSecond = overlay("equal-second", zIndex = 5, groupId = "ordered")

        store.addOverlay(high)
        store.addOverlay(low)
        store.addOverlay(equalFirst)
        store.addOverlay(equalSecond)

        assertEquals(
            listOf("low", "equal-first", "equal-second", "high"),
            store.snapshot.overlayInstances.map(KLineOverlayInstance::id),
        )

        store.updateOverlay(high.copy(zIndex = -3))
        store.selectOverlay(equalSecond.id)
        store.removeOverlay(equalFirst.id)

        assertEquals(
            listOf("high", "low", "equal-second"),
            store.snapshot.overlayInstances.map(KLineOverlayInstance::id),
        )
        assertEquals(equalSecond.id, store.snapshot.selectedOverlayId)
        store.removeOverlayGroup("ordered")
        assertEquals(emptyList(), store.snapshot.overlayInstances)
        assertEquals(null, store.snapshot.selectedOverlayId)
    }

    @Test
    fun themeOnlyAdvancesStyleRevisionForARealChange() {
        val store = KLineStore()
        val initial = store.snapshot.styleRevision

        store.setTheme(KLineTheme.LIGHT)
        assertEquals(initial, store.snapshot.styleRevision)
        store.setTheme(KLineTheme.DARK)
        assertEquals(initial + 1, store.snapshot.styleRevision)
        assertEquals(KLineTheme.DARK, store.snapshot.theme)
    }

    @Test
    fun oneThrowingIndicatorIsIsolatedAndReportsContext() {
        val errors = mutableListOf<KLineError>()
        val good = object : KLineIndicatorTemplate {
            override val name = "GOOD"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure("value", "Value", KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>) = KLineIndicatorResult(
                name, series, listOf(KLineIndicatorFigureResult("value", KLineIndicatorFigureType.LINE, bars.map { it.close })),
            )
        }
        val bad = object : KLineIndicatorTemplate {
            override val name = "BAD"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = emptyList<KLineIndicatorFigure>()
            override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult = error("broken formula")
        }
        val store = KLineStore(errors::add, KLineExtensionRegistry(listOf(good, bad)))
        store.setIndicator(KLineIndicatorInstance("bad-1", "BAD", "price", emptyList(), 2))
        store.setIndicator(KLineIndicatorInstance("good-1", "GOOD", "price", emptyList(), 2))

        store.replaceAll(listOf(bar(1, 12.0)))

        assertEquals(setOf("good-1"), store.snapshot.indicatorResults.keys)
        assertEquals(KLineErrorCode.INDICATOR_CALCULATION_FAILED, errors.last().code)
        assertEquals("bad-1", errors.last().instanceId)
        assertEquals("BAD", errors.last().templateName)
    }

    @Test
    fun errorObserversReceiveOnlyWhileSubscribed() {
        val observed = mutableListOf<KLineError>()
        val store = KLineStore()
        val subscription = store.observeErrors(observed::add)
        store.replaceAll(listOf(KLineBar(1, 2.0, 1.0, 1.0, 1.0)))
        subscription.cancel()
        store.replaceAll(listOf(KLineBar(2, 2.0, 1.0, 1.0, 1.0)))

        assertEquals(1, observed.size)
        assertEquals(KLineErrorCode.INVALID_DATA, observed.single().code)
    }

    @Test
    fun dataPublishingOnlyAdvancesIndicatorRevisionWhenResultsChange() {
        val store = KLineStore()
        store.setIndicator(KLineIndicatorInstance("ma", "MA", "price", listOf(1.0), 2))
        store.replaceAll(listOf(bar(1, 10.0)))
        val afterFirstResult = store.snapshot.indicatorRevision

        store.replaceAll(listOf(bar(1, 10.0)))
        assertEquals(afterFirstResult, store.snapshot.indicatorRevision)
        store.replaceAll(listOf(bar(2, 10.0)))
        assertEquals(afterFirstResult, store.snapshot.indicatorRevision)
        store.replaceAll(listOf(bar(2, 11.0)))
        assertEquals(afterFirstResult + 1, store.snapshot.indicatorRevision)

        store.setIndicator(store.snapshot.indicatorInstances.single().copy(precision = 3))
        assertEquals(afterFirstResult + 2, store.snapshot.indicatorRevision)
    }

    @Test
    fun tailDataChangeReusesIndicatorIdentityWhenAffectedValuesAreEqual() {
        val store = KLineStore()
        store.setIndicator(KLineIndicatorInstance("ma", "MA", "price", listOf(1.0), 2))
        store.replaceAll(listOf(bar(1, 10.0).copy(volume = 1.0)))
        val result = store.snapshot.indicatorResults.getValue("ma")
        val revision = store.snapshot.indicatorRevision

        store.applyRealtime(bar(1, 10.0).copy(volume = 2.0))

        assertSame(result, store.snapshot.indicatorResults.getValue("ma"))
        assertEquals(revision, store.snapshot.indicatorRevision)
    }

    @Test
    fun deterministicIndicatorFailuresAreCachedPerInstanceAndRetryAfterKeyChanges() {
        var failureInvocations = 0
        val errors = mutableListOf<KLineError>()
        val bad = object : KLineIndicatorTemplate {
            override val name = "CACHED_BAD"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = emptyList<KLineIndicatorFigure>()
            override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
                failureInvocations++
                throw IllegalStateException("deterministic failure")
            }
        }
        val good = object : KLineIndicatorTemplate {
            override val name = "CACHED_GOOD"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure("v", "v", KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>) = KLineIndicatorResult(
                name, series, listOf(KLineIndicatorFigureResult("v", KLineIndicatorFigureType.LINE, bars.map { it.close })),
            )
        }
        val registry = KLineExtensionRegistry(listOf(bad, good))
        val store = KLineStore(errors::add, registry)
        store.setPanes(listOf(KLinePane("price", KLinePaneKind.PRICE, 0, 1.0, 0.0, yAxes = listOf(KLineYAxis("y", 0.0, 1.0)))))
        val badInstance = KLineIndicatorInstance("bad", bad.name, "price", emptyList(), 2)
        val goodInstance = KLineIndicatorInstance("good", good.name, "price", emptyList(), 2)

        store.setIndicator(badInstance)
        store.setIndicator(goodInstance)
        store.setIndicator(goodInstance.copy(precision = 3))
        store.restoreState(KLineChartState(
            store.snapshot.viewport, store.snapshot.panes, store.snapshot.indicatorInstances,
            store.snapshot.overlayInstances, store.snapshot.theme, store.snapshot.formatters,
        ))
        assertEquals(1, failureInvocations)
        assertEquals(1, errors.count { it.code == KLineErrorCode.INDICATOR_CALCULATION_FAILED })

        store.replaceAll(listOf(bar(1, 10.0)))
        assertEquals(2, failureInvocations)
        assertEquals(2, errors.count { it.code == KLineErrorCode.INDICATOR_CALCULATION_FAILED })
        store.setIndicator(goodInstance.copy(precision = 4))
        assertEquals(2, failureInvocations)

        registry.register(bad)
        store.setIndicator(goodInstance.copy(precision = 5))
        assertEquals(3, failureInvocations)
        assertEquals(3, errors.count { it.code == KLineErrorCode.INDICATOR_CALCULATION_FAILED })

        store.setIndicator(badInstance.copy(params = listOf(1.0)))
        assertEquals(4, failureInvocations)
        store.setIndicator(badInstance)
        assertEquals(5, failureInvocations)
        store.removeIndicator(badInstance.id)
        store.setIndicator(badInstance)
        assertEquals(6, failureInvocations)
    }

    @Test
    fun fatalErrorsFromIndicatorsAndCallbacksPropagate() {
        class Fatal : Error("fatal")
        val fatalTemplate = object : KLineIndicatorTemplate {
            override val name = "FATAL"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = emptyList<KLineIndicatorFigure>()
            override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult = throw Fatal()
        }
        val indicatorStore = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(fatalTemplate)))
        assertFailsWith<Fatal> {
            indicatorStore.setIndicator(KLineIndicatorInstance("fatal", "FATAL", "price", emptyList(), 2))
        }

        val callbackStore = KLineStore(onError = { throw Fatal() })
        assertFailsWith<Fatal> { callbackStore.replaceAll(listOf(KLineBar(1, 2.0, 1.0, 1.0, 1.0))) }

        val observerStore = KLineStore()
        observerStore.observe { _, _ -> throw Fatal() }
        assertFailsWith<Fatal> { observerStore.replaceAll(listOf(bar(1, 1.0))) }

        val cancellationStore = KLineStore(onError = { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> {
            cancellationStore.replaceAll(listOf(KLineBar(1, 2.0, 1.0, 1.0, 1.0)))
        }
    }

    @Test
    fun indicatorResultsAreDeepImmutableAcrossTemplateReuseAndStoreRecalculation() {
        val mutableValues = MutableList<Double?>(20_000) { it.toDouble() }
        val mutableFigures = mutableListOf<KLineIndicatorFigureResult>()
        val template = object : KLineIndicatorTemplate {
            override val name = "MUTABLE_OUTPUT"
            override val defaultParams = emptyList<Double>()
            override val series = KLineIndicatorSeries.PRICE
            override val figures = listOf(KLineIndicatorFigure("v", "v", KLineIndicatorFigureType.LINE))
            override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
                mutableFigures.clear()
                mutableFigures += KLineIndicatorFigureResult("v", KLineIndicatorFigureType.LINE, mutableValues.take(bars.size))
                return KLineIndicatorResult(name, series, mutableFigures)
            }
        }
        val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
        store.replaceAll((0L until 20_000L).map { bar(it, it.toDouble()) })
        store.setIndicator(KLineIndicatorInstance("mutable", template.name, "price", emptyList(), 2))
        val firstSnapshot = store.snapshot
        val firstRevision = firstSnapshot.indicatorRevision

        mutableValues[0] = -2.0
        mutableFigures.clear()
        assertEquals(20_000, firstSnapshot.indicatorResults.getValue("mutable").figures.single().values.size)
        assertEquals(0.0, firstSnapshot.indicatorResults.getValue("mutable").figures.single().values.first())
        assertFailsWith<ClassCastException> {
            @Suppress("UNCHECKED_CAST")
            (firstSnapshot.indicatorResults.getValue("mutable").figures as MutableList<KLineIndicatorFigureResult>).clear()
        }
        assertFailsWith<ClassCastException> {
            @Suppress("UNCHECKED_CAST")
            (firstSnapshot.indicatorResults.getValue("mutable").figures.single().values as MutableList<Double?>)[0] = 9.0
        }

        store.replaceAll(listOf(bar(1, 1.0)))
        assertEquals(-2.0, store.snapshot.indicatorResults.getValue("mutable").figures.single().values.first())
        assertEquals(0.0, firstSnapshot.indicatorResults.getValue("mutable").figures.single().values.first())
        assertEquals(firstRevision + 1, store.snapshot.indicatorRevision)
    }

    private fun bar(timestamp: Long, close: Double): KLineBar = KLineBar(
        timestamp = timestamp,
        open = close,
        high = close,
        low = close,
        close = close,
    )

    private fun entropyBar(timestamp: Long): KLineBar {
        val noise = ((timestamp * 1_103_515_245L + 12_345L) ushr 8) % 997L
        val close = 20.0 + noise / 37.0
        return KLineBar(timestamp, close - 0.7, close + 1.3, close - 1.1, close, noise + 1.0, (noise + 1.0) * close)
    }

    private fun assertIndicatorClose(expected: KLineIndicatorResult, actual: KLineIndicatorResult, message: String) {
        assertEquals(expected.templateName, actual.templateName, message)
        assertEquals(expected.series, actual.series, message)
        assertEquals(expected.figures.map { it.key to it.type }, actual.figures.map { it.key to it.type }, message)
        expected.figures.zip(actual.figures).forEach { (expectedFigure, actualFigure) ->
            assertEquals(expectedFigure.values.size, actualFigure.values.size, message)
            expectedFigure.values.zip(actualFigure.values).forEach { (left, right) ->
                if (left == null || right == null) assertEquals(left, right, message)
                else assertEquals(left, right, 1e-10, message)
            }
        }
    }

    private fun overlay(
        id: String,
        zIndex: Int,
        groupId: String?,
    ): KLineOverlayInstance = KLineOverlayInstance(
        id = id,
        templateName = "horizontal_line",
        groupId = groupId,
        paneId = "price",
        points = listOf(KLineOverlayPoint(1, 10.0)),
        zIndex = zIndex,
    )

    private class IncrementalRecordingTemplate : KLineIncrementalIndicatorTemplate {
        override val name = "INCREMENTAL_RECORDING"
        override val defaultParams = emptyList<Double>()
        override val series = KLineIndicatorSeries.PRICE
        override val figures = listOf(KLineIndicatorFigure("value", "value", KLineIndicatorFigureType.LINE))
        var lastContext: KLineIndicatorUpdateContext? = null
        var processedBars = 0

        override fun finiteLookback(params: List<Double>) = 1

        override fun outputAffectedRange(context: KLineIndicatorUpdateContext): IntRange? =
            if (context.change.kind == KLineDataChangeKind.TAIL_UPDATE) context.change.newAffectedRange else null

        override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
            processedBars = 0
            val values = object : AbstractList<Double?>() {
                override val size: Int get() = bars.size
                override fun get(index: Int): Double {
                    processedBars++
                    return bars[index].close
                }
            }
            return result(values)
        }

        override fun calculateIncremental(
            context: KLineIndicatorUpdateContext,
            params: List<Double>,
        ): KLineIndicatorResult {
            lastContext = context
            return super.calculateIncremental(context, params)
        }

        private fun result(values: List<Double?>) = KLineIndicatorResult(
            name, series, listOf(KLineIndicatorFigureResult("value", KLineIndicatorFigureType.LINE, values)),
        )
    }

    private class FallbackTemplate : KLineIndicatorTemplate {
        override val name = "FALLBACK"
        override val defaultParams = emptyList<Double>()
        override val series = KLineIndicatorSeries.PRICE
        override val figures = listOf(KLineIndicatorFigure("value", "value", KLineIndicatorFigureType.LINE))
        var fullCount = 0
        var incrementalMode = 0
        override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
            fullCount++
            if (incrementalMode == 3) error("full failed")
            return KLineIndicatorResult(name, series, listOf(KLineIndicatorFigureResult("value", KLineIndicatorFigureType.LINE, bars.map(KLineBar::close))))
        }
        override fun calculateIncremental(context: KLineIndicatorUpdateContext, params: List<Double>): KLineIndicatorResult = when (incrementalMode) {
            1 -> error("incremental failed")
            2 -> KLineIndicatorResult(name, series, listOf(KLineIndicatorFigureResult("value", KLineIndicatorFigureType.LINE, emptyList())))
            3 -> error("incremental and full failed")
            else -> calculate(context.newBars, params)
        }
    }
}
