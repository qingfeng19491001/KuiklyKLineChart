package com.tencent.kuiklybase.kline.controller

import com.tencent.kuiklybase.kline.axis.KLineYAxis
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineDataSession
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.layout.KLineRect
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayConfig
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle
import com.tencent.kuiklybase.kline.overlay.KLineOverlayMagnetMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.interaction.KLineInteractionState
import com.tencent.kuiklybase.kline.interaction.KLineInteractionSession
import com.tencent.kuiklybase.kline.overlay.KLineOverlayContext
import com.tencent.kuiklybase.kline.overlay.KLineOverlayDrawingMode
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigure
import com.tencent.kuiklybase.kline.overlay.KLineOverlayTemplate
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneKind
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import com.tencent.kuiklybase.kline.store.KLineStore
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode
import com.tencent.kuiklybase.kline.format.KLineFormatterOptions
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KLineChartControllerTest {
    @Test
    fun runtimeBoundariesCancelAndRollbackActiveInteractionsBeforeClearingTransientState() {
        val bars = (1L..40L).map(::bar)
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(bars), store))
        runtime.updatePlotRect(KLineRect(0.0, 0.0, 200.0, 400.0))
        val controller = KLineChartController()
        controller.attach(runtime)
        val overlay = KLineOverlayConfig(
            "segment",
            paneId = "price",
            points = listOf(KLineOverlayPoint(1_000L, 10.0), KLineOverlayPoint(2_000L, 20.0)),
        ).toInstance("kept")
        store.addOverlay(overlay)
        store.beginInteraction(KLineInteractionSession.DraggingOverlayPoint("kept", 0, overlay.points))
        store.updateOverlay(overlay.copy(points = listOf(KLineOverlayPoint(1_000L, 99.0), overlay.points[1])))

        controller.setMarket(KLineSymbol("000002.SZ"), KLinePeriod(1, KLinePeriodUnit.DAY))
        assertEquals(overlay.points, store.snapshot.overlayInstances.single().points)
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
        assertEquals(null, store.snapshot.selectedOverlayId)

        store.beginInteraction(KLineInteractionSession.DraggingOverlay("kept", overlay.points[0], overlay.points))
        store.updateOverlay(overlay.copy(points = listOf(KLineOverlayPoint(2_000L, 30.0), KLineOverlayPoint(3_000L, 40.0))))
        controller.resetViewport()
        assertEquals(overlay.points, store.snapshot.overlayInstances.single().points)
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)

        val panes = listOf(pane("price", KLinePaneKind.PRICE, 0), pane("volume", KLinePaneKind.INDICATOR, 1))
        store.setPanes(panes)
        val layouts = com.tencent.kuiklybase.kline.pane.KLinePaneLayoutEngine.layout(panes, KLineRect(0.0, 0.0, 200.0, 400.0), 0.0)
        runtime.interactionEngine.paneResize.beginResize(0, layouts[0].rect.bottom, layouts)
        runtime.interactionEngine.paneResize.updateResize(layouts[0].rect.bottom + 30.0)
        runtime.dispose()
        assertEquals(panes, store.snapshot.panes)
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
    }

    @Test
    fun controllerBeginsOverlayRegisteredInTheStoresExtensionRegistry() {
        val store = KLineStore()
        store.extensionRegistry.registerOverlay(object : KLineOverlayTemplate {
            override val name = "custom-marker"
            override val requiredPointCount = 1
            override val drawingMode = KLineOverlayDrawingMode.POINT_BY_POINT
            override fun createFigures(context: KLineOverlayContext): List<KLineOverlayFigure> = emptyList()
        })
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()

        val draftId = controller.beginOverlay("custom-marker")
        controller.attach(runtime)

        assertEquals(KLineInteractionState.DRAWING_OVERLAY, store.snapshot.interactionState)
        assertEquals(draftId, (store.snapshot.interactionSession as KLineInteractionSession.DrawingOverlay).draftId)
    }

    @Test
    fun queuedInteractionCommandsEnterDrawingAndDeleteTheSelectedOverlayThroughRuntime() {
        val bars = (1L..3L).map(::bar)
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(bars), store))
        val controller = KLineChartController()

        val draftId = controller.beginOverlay("segment")
        assertTrue(draftId.startsWith("overlay-"))
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
        controller.attach(runtime)
        assertEquals(KLineInteractionState.DRAWING_OVERLAY, store.snapshot.interactionState)
        assertEquals(draftId, (store.snapshot.interactionSession as com.tencent.kuiklybase.kline.interaction.KLineInteractionSession.DrawingOverlay).draftId)

        controller.cancelInteraction()
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
        val completedId = controller.createOverlay(
            KLineOverlayConfig("horizontal_line", paneId = "price", points = listOf(KLineOverlayPoint(1_000L, 10.0))),
        )
        store.selectOverlay(completedId)
        controller.deleteSelectedOverlay()
        assertEquals(emptyList(), store.snapshot.overlayInstances)
        assertEquals(null, store.snapshot.selectedOverlayId)
    }

    @Test
    fun queuedAndAttachedCommandsDriveViewportAndPanesThroughOneStore() {
        val bars = (0L until 100L).map(::bar)
        val store = KLineStore()
        val session = KLineDataSession(
            dataSource = StaticKLineDataSource(bars),
            store = store,
        )
        val runtime = KLineChartRuntime(store = store, dataSession = session)
        runtime.updatePlotRect(KLineRect(0.0, 0.0, 200.0, 400.0))
        val controller = KLineChartController()
        val pricePane = pane("price", KLinePaneKind.PRICE, 0)
        val volumePane = pane("volume", KLinePaneKind.INDICATOR, 1)

        controller.setMarket(
            symbol = KLineSymbol(ticker = "000001.SZ"),
            period = KLinePeriod(span = 1, unit = KLinePeriodUnit.DAY),
        )
        controller.setPane(pricePane)
        controller.zoom(2.0)
        controller.attach(runtime)

        assertEquals(20.0, store.snapshot.viewport!!.barSpace, 1e-9)
        assertEquals(listOf("price"), store.snapshot.panes.map(KLinePane::id))

        controller.scrollToTimestamp(50_000L)
        assertEquals(45.0, store.snapshot.viewport!!.startIndex, 1e-9)
        assertEquals(55.0, store.snapshot.viewport!!.endIndex, 1e-9)
        controller.scrollByBars(5.0)
        assertEquals(50.0, store.snapshot.viewport!!.startIndex, 1e-9)

        controller.setPane(volumePane)
        controller.movePane("volume", index = 0)
        controller.setPaneState("volume", KLinePaneState.MAXIMIZED)
        assertEquals(listOf("volume", "price"), store.snapshot.panes.map(KLinePane::id))
        assertEquals(KLinePaneState.MAXIMIZED, store.snapshot.panes.first().state)

        controller.detach(runtime)
        controller.scrollToLatest()
        assertEquals(60.0, store.snapshot.viewport!!.endIndex, 1e-9)
        controller.attach(runtime)
        assertEquals(99.0, store.snapshot.viewport!!.endIndex, 1e-9)
        controller.resetViewport()
        assertEquals(10.0, store.snapshot.viewport!!.barSpace, 1e-9)
    }

    @Test
    fun realtimeFollowAndHistoryPrependMaintainViewportInvariants() {
        val initialBars = (0L until 100L).map(::bar)
        val store = KLineStore()
        val session = KLineDataSession(StaticKLineDataSource(initialBars), store)
        val runtime = KLineChartRuntime(store, session)
        val rect = KLineRect(0.0, 0.0, 200.0, 400.0)
        runtime.updatePlotRect(rect)
        val controller = KLineChartController()
        controller.attach(runtime)
        controller.setMarket(
            KLineSymbol(ticker = "000001.SZ"),
            KLinePeriod(span = 1, unit = KLinePeriodUnit.DAY),
        )

        controller.scrollByBars(-10.0)
        store.applyRealtime(bar(100))
        assertEquals(89.0, store.snapshot.viewport!!.endIndex, 1e-9)
        assertEquals(-11.0, store.snapshot.viewport!!.rightOffset, 1e-9)

        controller.scrollToLatest()
        store.applyRealtime(bar(101))
        assertEquals(101.0, store.snapshot.viewport!!.endIndex, 1e-9)

        val beforePrependPixel = KLineXCoordinateSystem(
            rect,
            store.snapshot.viewport!!,
            store.snapshot.bars,
        ).timestampToPixel(50_000L)
        store.prepend(listOf(bar(-2), bar(-1)), hasMoreBefore = false)
        val afterPrependPixel = KLineXCoordinateSystem(
            rect,
            store.snapshot.viewport!!,
            store.snapshot.bars,
        ).timestampToPixel(50_000L)
        assertEquals(beforePrependPixel!!, afterPrependPixel!!, 1e-9)
    }

    @Test
    fun queuedIndicatorCommandsAddUpdateAndRemoveInstancesThroughTheStore() {
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource((1L..4L).map(::bar)), store))
        val controller = KLineChartController()
        val indicator = KLineIndicatorInstance(
            id = "price-ma",
            templateName = "MA",
            paneId = "price",
            params = listOf(3.0),
            precision = 2,
        )

        controller.addIndicator(indicator)
        controller.setMarket(KLineSymbol("000001.SZ"), KLinePeriod(1, KLinePeriodUnit.DAY))
        controller.attach(runtime)
        assertEquals(listOf(null, null, 10.0, 10.0), store.snapshot.indicatorResults.getValue("price-ma").figures.single().values)

        controller.updateIndicator(indicator.copy(params = listOf(2.0), visible = false))
        assertEquals(emptyMap(), store.snapshot.indicatorResults)
        controller.removeIndicator("price-ma")
        assertEquals(emptyList(), store.snapshot.indicatorInstances)
    }

    @Test
    fun queuedOverlayCommandsReturnStableIdsAndMutateTheStoreAfterAttach() {
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()
        val initial = KLineOverlayConfig(
            templateName = "segment",
            groupId = "analysis",
            paneId = "price",
            points = listOf(KLineOverlayPoint(1, 10.0), KLineOverlayPoint(2, 20.0)),
            magnetMode = KLineOverlayMagnetMode.WEAK,
            zIndex = 3,
            styles = mapOf("default" to KLineOverlayFigureStyle(color = "#ff0000")),
        )

        val firstId = controller.createOverlay(initial)
        val secondId = controller.createOverlay(initial.copy(groupId = null, zIndex = 4))
        controller.updateOverlay(firstId, initial.copy(visible = false, locked = true, zIndex = 8))

        assertTrue(firstId.endsWith("-1"))
        assertEquals(firstId.substringBeforeLast("-"), secondId.substringBeforeLast("-"))
        assertTrue(secondId.endsWith("-2"))
        assertEquals(emptyList(), store.snapshot.overlayInstances)

        controller.attach(runtime)
        assertEquals(listOf(secondId, firstId), store.snapshot.overlayInstances.map { it.id })
        val updatedFirst = store.snapshot.overlayInstances.first { it.id == firstId }
        assertEquals(false, updatedFirst.visible)
        assertEquals(true, updatedFirst.locked)
        assertEquals(8, updatedFirst.zIndex)
        assertEquals("#ff0000", updatedFirst.styles.getValue("default").color)

        controller.removeOverlay(secondId)
        assertEquals(listOf(firstId), store.snapshot.overlayInstances.map { it.id })
    }

    @Test
    fun rebuiltControllersAllocateDistinctOverlayIdsForTheSameStore() {
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val config = KLineOverlayConfig(
            templateName = "horizontal_line",
            paneId = "price",
            points = listOf(KLineOverlayPoint(1, 10.0)),
        )
        val firstController = KLineChartController()
        val firstId = firstController.createOverlay(config)
        firstController.attach(runtime)
        firstController.detach(runtime)

        val secondController = KLineChartController()
        secondController.attach(runtime)
        val secondId = secondController.createOverlay(config)

        assertNotEquals(firstId, secondId)
        assertTrue(firstId.startsWith("overlay-") && secondId.startsWith("overlay-"))
        assertEquals(setOf(firstId, secondId), store.snapshot.overlayInstances.map { it.id }.toSet())
    }

    @Test
    fun exportRequiresAttachmentAndRestoreQueuedBeforeAttachRestoresDurableState() {
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()
        assertFailsWith<IllegalStateException> { controller.exportState() }
        val state = KLineChartState(
            viewport = KLineViewport(2.0, 12.0, 8.0, -1.0),
            panes = listOf(pane("price", KLinePaneKind.PRICE, 0)),
            indicatorInstances = listOf(KLineIndicatorInstance("ma", "MA", "price", listOf(3.0), 2)),
            overlayInstances = listOf(KLineOverlayConfig("horizontal_line", paneId = "price", points = listOf(KLineOverlayPoint(1, 10.0))).toInstance("line")),
            theme = KLineTheme.DARK,
        )

        controller.restoreState(state)
        controller.attach(runtime)
        val exported = controller.exportState()

        assertEquals(state, exported)
        assertEquals(KLineTheme.DARK, store.snapshot.theme)
        assertEquals(emptyList(), store.snapshot.bars)
    }

    @Test
    fun restoreSkipsUnknownExtensionsDefensivelyAndDoesNotRestoreTransientInteraction() {
        val errors = mutableListOf<KLineError>()
        val store = KLineStore(errors::add)
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()
        controller.attach(runtime)
        val points = mutableListOf(KLineOverlayPoint(1, 10.0))
        val params = mutableListOf(3.0)
        val state = KLineChartState(
            viewport = null,
            panes = listOf(pane("price", KLinePaneKind.PRICE, 0)),
            indicatorInstances = listOf(
                KLineIndicatorInstance("known", "MA", "price", params, 2),
                KLineIndicatorInstance("unknown", "MISSING", "price", emptyList(), 2),
            ),
            overlayInstances = listOf(
                KLineOverlayConfig("horizontal_line", paneId = "price", points = points).toInstance("known-overlay"),
                KLineOverlayConfig("missing", paneId = "price", points = points).toInstance("unknown-overlay"),
            ),
            theme = KLineTheme.LIGHT,
        )
        params[0] = 99.0
        points[0] = KLineOverlayPoint(2, 20.0)
        store.beginInteraction(KLineInteractionSession.Panning(5.0, KLineViewport(0.0, 10.0, 8.0, 0.0)))

        controller.restoreState(state)

        assertEquals(listOf("known"), store.snapshot.indicatorInstances.map { it.id })
        assertEquals(listOf(3.0), store.snapshot.indicatorInstances.single().params)
        assertEquals(listOf("known-overlay"), store.snapshot.overlayInstances.map { it.id })
        assertEquals(1L, store.snapshot.overlayInstances.single().points.single().timestamp)
        assertEquals(KLineInteractionState.IDLE, store.snapshot.interactionState)
        assertEquals(emptyList(), errors.filter { it.code == KLineErrorCode.STATE_RESTORE_FAILED })
    }

    @Test
    fun structurallyInvalidRestoreFailsAtomicallyAndReportsOneError() {
        val errors = mutableListOf<KLineError>()
        val store = KLineStore(errors::add)
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()
        controller.attach(runtime)
        controller.setPane(pane("price", KLinePaneKind.PRICE, 0))
        store.beginInteraction(KLineInteractionSession.Panning(5.0, KLineViewport(0.0, 10.0, 8.0, 0.0)))
        val before = store.snapshot
        val duplicate = pane("dup", KLinePaneKind.PRICE, 0)

        controller.restoreState(KLineChartState(null, listOf(duplicate, duplicate), emptyList(), emptyList(), KLineTheme.DARK))

        assertEquals(before, store.snapshot)
        assertEquals(KLineErrorCode.STATE_RESTORE_FAILED, errors.single().code)
    }

    @Test
    fun successfulRestorePublishesOneAtomicSnapshotWhileCancellingInteraction() {
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()
        controller.attach(runtime)
        store.beginInteraction(KLineInteractionSession.Panning(5.0, KLineViewport(0.0, 10.0, 8.0, 0.0)))
        val observed = mutableListOf<com.tencent.kuiklybase.kline.store.KLineStoreSnapshot>()
        val subscription = store.observe { _, current -> observed += current }
        val target = KLineChartState(
            KLineViewport(2.0, 12.0, 9.0, -2.0),
            listOf(pane("price", KLinePaneKind.PRICE, 0)),
            emptyList(),
            emptyList(),
            KLineTheme.DARK,
        )

        controller.restoreState(target)
        subscription.cancel()

        assertEquals(1, observed.size)
        assertEquals(KLineInteractionState.IDLE, observed.single().interactionState)
        assertEquals(target.viewport, observed.single().viewport)
        assertEquals(target.theme, observed.single().theme)
    }

    @Test
    fun exportRollsBackTransientInteractionMutationsInTheReturnedState() {
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()
        controller.attach(runtime)
        val initial = KLineViewport(0.0, 10.0, 8.0, 0.0)
        store.setViewport(initial)
        store.beginInteraction(KLineInteractionSession.Panning(5.0, initial))
        store.setViewport(KLineViewport(3.0, 13.0, 8.0, -3.0))

        val state = controller.exportState()

        assertEquals(initial, state.viewport)
        assertEquals(KLineInteractionState.PANNING, store.snapshot.interactionState)
        assertEquals(3.0, store.snapshot.viewport!!.startIndex)
    }

    @Test
    fun resolvedFormattersAreStoreBackedStyleAndDurablePresentationState() {
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()
        controller.attach(runtime)
        val options = KLineFormatterOptions().apply {
            date = com.tencent.kuiklybase.kline.format.KLineFormatOptions(timeZone = com.tencent.kuiklybase.kline.format.KLineTimeZone.UTC)
            price = com.tencent.kuiklybase.kline.format.KLineFormatOptions(pricePrecision = 0)
            volume = com.tencent.kuiklybase.kline.format.KLineFormatOptions(volumePrecision = 1, language = com.tencent.kuiklybase.kline.format.KLineLanguage.CHINESE)
            turnover = com.tencent.kuiklybase.kline.format.KLineFormatOptions(turnoverPrecision = 1)
            percentage = com.tencent.kuiklybase.kline.format.KLineFormatOptions(percentagePrecision = 1, showPositiveSign = false)
            indicatorValue = com.tencent.kuiklybase.kline.format.KLineFormatOptions(fallbackText = "N/A")
        }
        val resolved = options.resolved()
        val initialRevision = store.snapshot.styleRevision

        controller.setFormatters(KLineFormatterOptions().resolved())
        assertEquals(initialRevision, store.snapshot.styleRevision)
        controller.setFormatters(resolved)
        options.price = com.tencent.kuiklybase.kline.format.KLineFormatOptions(pricePrecision = 4)

        assertEquals("1970-01-01 00:00", store.snapshot.formatters.formatDate(1))
        assertEquals("1", store.snapshot.formatters.formatPrice(1.0))
        assertEquals("1.0", store.snapshot.formatters.formatVolume(1.0))
        assertEquals("1.0", store.snapshot.formatters.formatTurnover(1.0))
        assertEquals("100.0%", store.snapshot.formatters.formatPercentage(1.0))
        assertEquals("N/A", store.snapshot.formatters.formatIndicatorValue(null, 2))
        assertEquals(initialRevision + 1, store.snapshot.styleRevision)
        controller.setFormatters(resolved)
        assertEquals(initialRevision + 1, store.snapshot.styleRevision)

        val exported = controller.exportState()
        controller.setFormatters(KLineFormatterOptions().resolved())
        controller.restoreState(exported)
        assertEquals("1", store.snapshot.formatters.formatPrice(1.0))
        assertEquals(resolved, controller.exportState().formatters)
    }

    @Test
    fun fatalObserverErrorsPropagateAcrossRuntimeRestore() {
        class Fatal : Error("fatal restore observer")
        val store = KLineStore()
        val runtime = KLineChartRuntime(store, KLineDataSession(StaticKLineDataSource(emptyList()), store))
        val controller = KLineChartController()
        controller.attach(runtime)
        store.observe { _, _ -> throw Fatal() }

        assertFailsWith<Fatal> {
            controller.restoreState(KLineChartState(null, emptyList(), emptyList(), emptyList(), KLineTheme.DARK))
        }
    }

    private fun pane(
        id: String,
        kind: KLinePaneKind,
        order: Int,
    ): KLinePane = KLinePane(
        id = id,
        kind = kind,
        order = order,
        weight = if (kind == KLinePaneKind.PRICE) 3.0 else 1.0,
        minHeight = if (kind == KLinePaneKind.PRICE) 100.0 else 60.0,
        yAxes = listOf(KLineYAxis(id = "$id-axis", minValue = 1.0, maxValue = 100.0)),
    )

    private fun bar(index: Long): KLineBar = KLineBar(
        timestamp = index * 1_000L,
        open = 10.0,
        high = 10.0,
        low = 10.0,
        close = 10.0,
    )
}
