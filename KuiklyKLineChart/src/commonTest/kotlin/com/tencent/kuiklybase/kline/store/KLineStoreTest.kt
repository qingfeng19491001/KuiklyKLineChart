package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
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

class KLineStoreTest {
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
    fun realtimeBarReplacesTailOrAppendsNewTail() {
        val store = KLineStore()
        store.replaceAll(listOf(bar(1, 10.0), bar(2, 20.0)))

        store.applyRealtime(bar(2, 22.0))
        store.applyRealtime(bar(3, 30.0))

        assertEquals(listOf(10.0, 22.0, 30.0), store.snapshot.bars.map(KLineBar::close))
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
                mutableFigures += KLineIndicatorFigureResult("v", KLineIndicatorFigureType.LINE, mutableValues)
                return KLineIndicatorResult(name, series, mutableFigures)
            }
        }
        val store = KLineStore(extensionRegistry = KLineExtensionRegistry(listOf(template)))
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
}
