package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
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
