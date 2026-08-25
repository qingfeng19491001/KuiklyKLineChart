package com.tencent.kuiklybase.kline.data

import com.tencent.kuiklybase.kline.store.KLineLoadPhase
import com.tencent.kuiklybase.kline.store.KLineStore

internal class KLineDataSession(
    private val dataSource: KLineDataSource,
    private val store: KLineStore,
    private val initialLimit: Int = 200,
    private val pageLimit: Int = 200,
) {
    private var generation: Long = 0
    private var initialLoad: KLineCancelable? = null
    private var beforeLoad: KLineCancelable? = null
    private var afterLoad: KLineCancelable? = null
    private var realtimeSubscription: KLineCancelable? = null

    init {
        require(initialLimit > 0) { "Initial load limit must be positive" }
        require(pageLimit > 0) { "Page load limit must be positive" }
    }

    fun setMarket(
        symbol: KLineSymbol,
        period: KLinePeriod,
    ) {
        cancelActiveWork()
        generation += 1
        val requestGeneration = generation
        store.reset(symbol, period)
        store.setLoadPhase(KLineLoadDirection.INITIAL, KLineLoadPhase.LOADING)
        initialLoad = dataSource.load(
            request = KLineLoadRequest(
                symbol = symbol,
                period = period,
                direction = KLineLoadDirection.INITIAL,
                limit = initialLimit,
            ),
            callback = KLineLoadCallback { result ->
                if (requestGeneration != generation) return@KLineLoadCallback
                when (result) {
                    is KLineLoadResult.Success -> {
                        store.replaceAll(
                            bars = result.page.bars,
                            hasMoreBefore = result.page.hasMoreBefore,
                            hasMoreAfter = result.page.hasMoreAfter,
                        )
                        store.setLoadPhase(KLineLoadDirection.INITIAL, KLineLoadPhase.IDLE)
                        subscribe(symbol, period, requestGeneration)
                    }

                    is KLineLoadResult.Failure -> store.onLoadFailure(
                        direction = KLineLoadDirection.INITIAL,
                        message = result.message,
                    )
                }
            },
        )
    }

    fun loadBefore(): Boolean = loadPage(KLineLoadDirection.BEFORE)

    fun loadAfter(): Boolean = loadPage(KLineLoadDirection.AFTER)

    fun retryInitial(): Boolean {
        val current = store.snapshot
        if (current.loadState.initial != KLineLoadPhase.FAILED) return false
        val symbol = current.symbol ?: return false
        val period = current.period ?: return false
        setMarket(symbol, period)
        return true
    }

    fun close() {
        cancelActiveWork()
        generation += 1
    }

    private fun loadPage(direction: KLineLoadDirection): Boolean {
        require(direction != KLineLoadDirection.INITIAL) { "Use setMarket for initial loading" }
        val current = store.snapshot
        val symbol = current.symbol ?: return false
        val period = current.period ?: return false
        val canLoad = when (direction) {
            KLineLoadDirection.BEFORE -> current.hasMoreBefore &&
                current.loadState.before != KLineLoadPhase.LOADING
            KLineLoadDirection.AFTER -> current.hasMoreAfter &&
                current.loadState.after != KLineLoadPhase.LOADING
            KLineLoadDirection.INITIAL -> false
        }
        if (!canLoad) return false
        val anchorTimestamp = when (direction) {
            KLineLoadDirection.BEFORE -> current.bars.firstOrNull()?.timestamp
            KLineLoadDirection.AFTER -> current.bars.lastOrNull()?.timestamp
            KLineLoadDirection.INITIAL -> null
        } ?: return false
        val requestGeneration = generation
        store.setLoadPhase(direction, KLineLoadPhase.LOADING)
        val cancelable = dataSource.load(
            request = KLineLoadRequest(
                symbol = symbol,
                period = period,
                direction = direction,
                anchorTimestamp = anchorTimestamp,
                limit = pageLimit,
            ),
            callback = KLineLoadCallback { result ->
                if (requestGeneration != generation) return@KLineLoadCallback
                when (result) {
                    is KLineLoadResult.Success -> {
                        when (direction) {
                            KLineLoadDirection.BEFORE -> store.prepend(
                                bars = result.page.bars,
                                hasMoreBefore = result.page.hasMoreBefore,
                            )
                            KLineLoadDirection.AFTER -> store.append(
                                bars = result.page.bars,
                                hasMoreAfter = result.page.hasMoreAfter,
                            )
                            KLineLoadDirection.INITIAL -> Unit
                        }
                        store.setLoadPhase(direction, KLineLoadPhase.IDLE)
                    }

                    is KLineLoadResult.Failure -> store.onLoadFailure(direction, result.message)
                }
            },
        )
        when (direction) {
            KLineLoadDirection.BEFORE -> beforeLoad = cancelable
            KLineLoadDirection.AFTER -> afterLoad = cancelable
            KLineLoadDirection.INITIAL -> Unit
        }
        return true
    }

    private fun subscribe(
        symbol: KLineSymbol,
        period: KLinePeriod,
        requestGeneration: Long,
    ) {
        realtimeSubscription?.cancel()
        realtimeSubscription = dataSource.subscribe(
            symbol = symbol,
            period = period,
            listener = KLineRealtimeListener { event ->
                if (requestGeneration != generation) return@KLineRealtimeListener
                when (event) {
                    is KLineRealtimeEvent.Bar -> store.applyRealtime(event.bar)
                    is KLineRealtimeEvent.Failure -> store.onRealtimeFailure(event.message)
                }
            },
        )
    }

    private fun cancelActiveWork() {
        initialLoad?.cancel()
        beforeLoad?.cancel()
        afterLoad?.cancel()
        realtimeSubscription?.cancel()
        initialLoad = null
        beforeLoad = null
        afterLoad = null
        realtimeSubscription = null
    }
}
