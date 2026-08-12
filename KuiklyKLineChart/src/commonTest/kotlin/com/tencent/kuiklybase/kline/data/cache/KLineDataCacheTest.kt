package com.tencent.kuiklybase.kline.data.cache

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.data.KLineLoadRequest
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLinePeriodUnit
import com.tencent.kuiklybase.kline.data.KLineSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KLineDataCacheTest {
    private val symbol = KLineSymbol("TEST", "测试")
    private val period = KLinePeriod(1, KLinePeriodUnit.DAY)
    private val key = KLineCacheKey(symbol, period)

    private fun bars(count: Int, startTs: Long = 1_700_000_000_000L, step: Long = 86_400_000L): List<KLineBar> =
        List(count) { i ->
            KLineBar(
                timestamp = startTs + i * step,
                open = 10.0 + i,
                high = 11.0 + i,
                low = 9.0 + i,
                close = 10.5 + i,
                volume = 1_000_000.0 + i,
            )
        }

    @Test
    fun putAndGetRangeReturnsCorrectSlice() {
        var t = 0L
        val clock = { t }
        val cache = KLineDataCache.create(clock = clock)
        val data = bars(count = 10)
        cache.put(key, data)
        val range = cache.getRange(key, fromTsInclusive = data[3].timestamp, toTsInclusive = data[6].timestamp)
        assertEquals(4, range.size)
        assertEquals(data[3].timestamp, range.first().timestamp)
        assertEquals(data[6].timestamp, range.last().timestamp)
    }

    @Test
    fun storeAndLookupInitialPageServesCache() {
        var t = 0L
        val cache = KLineDataCache.create(clock = { t })
        val data = bars(count = 200)
        cache.storePage(
            key,
            KLineLoadRequest(symbol, period, KLineLoadDirection.INITIAL, limit = 100),
            com.tencent.kuiklybase.kline.data.KLineLoadPage(data, hasMoreBefore = true, hasMoreAfter = false),
        )
        val request = KLineLoadRequest(symbol, period, KLineLoadDirection.INITIAL, limit = 30)
        assertTrue(cache.canAnswer(request))
        val page = cache.lookupPage(key, request)
        assertNotNull(page)
        assertEquals(30, page.bars.size)
        assertEquals(data.last().timestamp, page.bars.last().timestamp)
    }

    @Test
    fun ttlExpiryEvictsEntry() {
        var now = 1_000L
        val cache = KLineDataCache.create(maxEntries = 4, ttlMillis = 5L, clock = { now })
        cache.put(key, bars(10))
        assertEquals(1, cache.stats().entries)
        now = 10_000L
        val stats = cache.stats()
        assertEquals(0, stats.entries)
        assertTrue(stats.evictions >= 1)
    }

    @Test
    fun capacityEvictsOldestAccessOrder() {
        var t = 0L
        val cache = KLineDataCache.create(maxEntries = 2, ttlMillis = Long.MAX_VALUE, clock = { t })
        val keys = (0..2).map { i ->
            KLineCacheKey(KLineSymbol("S$i", "n$i"), period)
        }
        cache.put(keys[0], bars(2))
        cache.put(keys[1], bars(2))
        cache.getRange(keys[0])
        cache.put(keys[2], bars(2))
        assertTrue(cache.getRange(keys[0]).isNotEmpty())
        assertTrue(cache.getRange(keys[2]).isNotEmpty())
        assertTrue(cache.getRange(keys[1]).isEmpty(), "keys[1] should have been evicted as LRU")
        assertEquals(1, cache.stats().evictions)
    }

    @Test
    fun mergeBarsUpdatesByTimestamp() {
        var t = 0L
        val cache = KLineDataCache.create(clock = { t })
        val a = bars(5, startTs = 1000L, step = 100L)
        cache.put(key, a)
        val replacementThird = KLineBar(
            timestamp = a[2].timestamp,
            open = 99.0, high = 99.0, low = 99.0, close = 99.0, volume = 99.0,
        )
        cache.put(key, listOf(replacementThird))
        val all = cache.getRange(key)
        assertEquals(5, all.size)
        assertEquals(99.0, all[2].open)
    }

    @Test
    fun decorateDataSourceReturnsCacheHits() {
        var t = 0L
        var callCount = 0
        val fake = object : com.tencent.kuiklybase.kline.data.KLineDataSource {
            override fun load(
                request: KLineLoadRequest,
                callback: com.tencent.kuiklybase.kline.data.KLineLoadCallback,
            ): com.tencent.kuiklybase.kline.data.KLineCancelable {
                callCount += 1
                val bars = bars(count = request.limit)
                callback.onResult(
                    com.tencent.kuiklybase.kline.data.KLineLoadResult.Success(
                        com.tencent.kuiklybase.kline.data.KLineLoadPage(bars, hasMoreBefore = true, hasMoreAfter = false),
                    ),
                )
                return com.tencent.kuiklybase.kline.data.KLineCancelable {}
            }
            override fun subscribe(
                symbol: KLineSymbol,
                period: KLinePeriod,
                listener: com.tencent.kuiklybase.kline.data.KLineRealtimeListener,
            ): com.tencent.kuiklybase.kline.data.KLineCancelable = com.tencent.kuiklybase.kline.data.KLineCancelable {}
        }
        val cache = KLineDataCache.create(clock = { t })
        val decorated = cache.decorate(fake)
        val req = KLineLoadRequest(symbol, period, KLineLoadDirection.INITIAL, limit = 50)
        decorated.load(req) { }
        assertEquals(1, callCount)
        decorated.load(req) { }
        assertEquals(1, callCount, "Second load should be served from cache")
        assertEquals(1, cache.stats().hits)
        assertEquals(1, cache.stats().misses)
    }
}
