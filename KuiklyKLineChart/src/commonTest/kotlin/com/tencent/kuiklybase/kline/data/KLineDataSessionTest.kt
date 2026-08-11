package com.tencent.kuiklybase.kline.data

import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode
import com.tencent.kuiklybase.kline.store.KLineLoadPhase
import com.tencent.kuiklybase.kline.store.KLineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KLineDataSessionTest {
    @Test
    fun switchingMarketCancelsPreviousWorkAndIgnoresLateCallbacks() {
        val source = FakeKLineDataSource()
        val store = KLineStore()
        val session = KLineDataSession(dataSource = source, store = store)
        val day = KLinePeriod(span = 1, unit = KLinePeriodUnit.DAY)
        val firstSymbol = KLineSymbol(ticker = "000001.SZ")
        val secondSymbol = KLineSymbol(ticker = "600000.SH")

        session.setMarket(firstSymbol, day)
        val firstLoad = source.loads.single()
        session.setMarket(secondSymbol, day)

        assertTrue(firstLoad.cancelled)
        firstLoad.succeed(pageOf(bar(1, 10.0)))
        assertEquals(secondSymbol, store.snapshot.symbol)
        assertEquals(emptyList(), store.snapshot.bars)

        source.loads.last().succeed(pageOf(bar(2, 20.0)))
        assertEquals(listOf(2L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(secondSymbol, source.subscriptions.single().symbol)
    }

    @Test
    fun historyAndRealtimeUpdatesFlowThroughTheSameStore() {
        val source = FakeKLineDataSource()
        val store = KLineStore()
        val session = KLineDataSession(dataSource = source, store = store, pageLimit = 50)
        val symbol = KLineSymbol(ticker = "000001.SZ")
        val day = KLinePeriod(span = 1, unit = KLinePeriodUnit.DAY)

        session.setMarket(symbol, day)
        source.loads.single().succeed(
            KLineLoadPage(
                bars = listOf(bar(3, 30.0), bar(4, 40.0)),
                hasMoreBefore = true,
                hasMoreAfter = true,
            ),
        )
        session.loadBefore()
        val before = source.loads.last()
        assertEquals(KLineLoadDirection.BEFORE, before.request.direction)
        assertEquals(3L, before.request.anchorTimestamp)
        assertEquals(50, before.request.limit)
        before.succeed(
            KLineLoadPage(
                bars = listOf(bar(1, 10.0), bar(2, 20.0)),
                hasMoreBefore = false,
                hasMoreAfter = true,
            ),
        )

        session.loadAfter()
        val after = source.loads.last()
        assertEquals(KLineLoadDirection.AFTER, after.request.direction)
        assertEquals(4L, after.request.anchorTimestamp)
        after.succeed(
            KLineLoadPage(
                bars = listOf(bar(5, 50.0)),
                hasMoreBefore = false,
                hasMoreAfter = false,
            ),
        )

        val realtime = source.subscriptions.single()
        realtime.emit(bar(5, 55.0))
        realtime.emit(bar(6, 60.0))
        realtime.emit(bar(4, 400.0))

        assertEquals(
            listOf(10.0, 20.0, 30.0, 40.0, 55.0, 60.0),
            store.snapshot.bars.map(KLineBar::close),
        )
        assertEquals(false, store.snapshot.hasMoreBefore)
        assertEquals(false, store.snapshot.hasMoreAfter)
    }

    @Test
    fun failuresAreReportedWithoutClearingUsableHistoryAndInitialLoadCanRetry() {
        val source = FakeKLineDataSource()
        val errors = mutableListOf<KLineError>()
        val store = KLineStore(onError = errors::add)
        val session = KLineDataSession(dataSource = source, store = store)
        val symbol = KLineSymbol(ticker = "000001.SZ")
        val day = KLinePeriod(span = 1, unit = KLinePeriodUnit.DAY)

        session.setMarket(symbol, day)
        source.loads.last().fail("initial unavailable")
        assertEquals(KLineLoadPhase.FAILED, store.snapshot.loadState.initial)
        assertEquals(KLineErrorCode.INITIAL_LOAD_FAILED, errors.last().code)

        assertTrue(session.retryInitial())
        source.loads.last().succeed(
            KLineLoadPage(
                bars = listOf(bar(2, 20.0), bar(3, 30.0)),
                hasMoreBefore = true,
                hasMoreAfter = false,
            ),
        )
        session.loadBefore()
        source.loads.last().fail("history unavailable")
        assertEquals(listOf(2L, 3L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(KLineLoadPhase.FAILED, store.snapshot.loadState.before)
        assertEquals(KLineErrorCode.HISTORY_LOAD_FAILED, errors.last().code)

        source.subscriptions.single().fail("stream unavailable")
        assertEquals(listOf(2L, 3L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(KLineErrorCode.REALTIME_SUBSCRIPTION_FAILED, errors.last().code)
    }

    @Test
    fun staticDataUsesTheSameInitialAndHistoricalLoadingContract() {
        val source = StaticKLineDataSource(
            bars = listOf(
                bar(5, 50.0),
                bar(1, 10.0),
                bar(4, 40.0),
                bar(2, 20.0),
                bar(3, 30.0),
            ),
        )
        val store = KLineStore()
        val session = KLineDataSession(
            dataSource = source,
            store = store,
            initialLimit = 2,
            pageLimit = 2,
        )

        session.setMarket(
            symbol = KLineSymbol(ticker = "000001.SZ"),
            period = KLinePeriod(span = 1, unit = KLinePeriodUnit.DAY),
        )
        assertEquals(listOf(4L, 5L), store.snapshot.bars.map(KLineBar::timestamp))
        assertTrue(store.snapshot.hasMoreBefore)

        assertTrue(session.loadBefore())
        assertEquals(listOf(2L, 3L, 4L, 5L), store.snapshot.bars.map(KLineBar::timestamp))
        assertTrue(session.loadBefore())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.snapshot.bars.map(KLineBar::timestamp))
        assertEquals(false, store.snapshot.hasMoreBefore)
    }

    private fun pageOf(vararg bars: KLineBar): KLineLoadPage = KLineLoadPage(
        bars = bars.toList(),
        hasMoreBefore = true,
        hasMoreAfter = false,
    )

    private fun bar(timestamp: Long, close: Double): KLineBar = KLineBar(
        timestamp = timestamp,
        open = close,
        high = close,
        low = close,
        close = close,
    )

    private class FakeKLineDataSource : KLineDataSource {
        val loads = mutableListOf<PendingLoad>()
        val subscriptions = mutableListOf<PendingSubscription>()

        override fun load(
            request: KLineLoadRequest,
            callback: KLineLoadCallback,
        ): KLineCancelable = PendingLoad(request, callback).also(loads::add)

        override fun subscribe(
            symbol: KLineSymbol,
            period: KLinePeriod,
            listener: KLineRealtimeListener,
        ): KLineCancelable = PendingSubscription(symbol, period, listener).also(subscriptions::add)
    }

    private class PendingLoad(
        val request: KLineLoadRequest,
        private val callback: KLineLoadCallback,
    ) : KLineCancelable {
        var cancelled: Boolean = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun succeed(page: KLineLoadPage) {
            callback.onResult(KLineLoadResult.Success(page))
        }

        fun fail(message: String) {
            callback.onResult(KLineLoadResult.Failure(message))
        }
    }

    private class PendingSubscription(
        val symbol: KLineSymbol,
        val period: KLinePeriod,
        val listener: KLineRealtimeListener,
    ) : KLineCancelable {
        var cancelled: Boolean = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun emit(bar: KLineBar) {
            listener.onEvent(KLineRealtimeEvent.Bar(bar))
        }

        fun fail(message: String) {
            listener.onEvent(KLineRealtimeEvent.Failure(message))
        }
    }
}
