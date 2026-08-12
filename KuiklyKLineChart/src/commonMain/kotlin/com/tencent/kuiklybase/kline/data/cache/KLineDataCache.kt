package com.tencent.kuiklybase.kline.data.cache

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineCancelable
import com.tencent.kuiklybase.kline.data.KLineLoadCallback
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.data.KLineLoadPage
import com.tencent.kuiklybase.kline.data.KLineLoadRequest
import com.tencent.kuiklybase.kline.data.KLineLoadResult
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLineRealtimeEvent
import com.tencent.kuiklybase.kline.data.KLineRealtimeListener
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.KLineDataSource

data class KLineCacheKey(val symbol: KLineSymbol, val period: KLinePeriod) {
    override fun toString(): String = "${symbol.ticker}_${period.span}_${period.unit}"
}

internal data class KLineCacheEntry(
    val key: KLineCacheKey,
    val ttlMillis: Long,
    val createdAt: Long,
) {
    val bars: MutableList<KLineBar> = mutableListOf()
    var hasMoreBefore: Boolean = true
    var hasMoreAfter: Boolean = true
    var lastAccessAt: Long = createdAt

    val size: Int get() = bars.size
    fun isExpired(now: Long): Boolean = now - lastAccessAt > ttlMillis
    fun touch(now: Long) { lastAccessAt = now }
}

data class KLineDataCacheStats(
    val entries: Int,
    val totalBars: Long,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
)

class KLineDataCache internal constructor(
    private val maxEntries: Int,
    private val maxBarsPerEntry: Int,
    private val defaultTtlMillis: Long,
    private val clock: () -> Long,
) {
    private val entries = LinkedHashMap<KLineCacheKey, KLineCacheEntry>()
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    fun stats(): KLineDataCacheStats {
        val now = clock()
        evictExpired(now)
        var totalBars = 0L
        entries.values.forEach { e -> totalBars += e.size.toLong() }
        return KLineDataCacheStats(entries.size, totalBars, hits, misses, evictions)
    }

    fun invalidate(key: KLineCacheKey) { entries.remove(key) }
    fun invalidateAll() { entries.clear() }

    fun put(key: KLineCacheKey, bars: List<KLineBar>) {
        val now = clock()
        val entry = getOrCreate(key, now)
        mergeBars(entry, bars)
        enforceCap()
        bumpToMru(entry, now)
    }

    fun getRange(key: KLineCacheKey, fromTsInclusive: Long? = null, toTsInclusive: Long? = null): List<KLineBar> {
        val now = clock()
        evictExpired(now)
        val entry = entries[key] ?: return emptyList()
        bumpToMru(entry, now)
        val list = entry.bars
        if (list.isEmpty()) return emptyList()
        var start = 0
        var endExclusive = list.size
        if (fromTsInclusive != null) {
            start = firstIndexAtOrAfter(list, fromTsInclusive)
        }
        if (toTsInclusive != null) {
            endExclusive = firstIndexAtOrAfter(list, toTsInclusive + 1L)
        }
        if (start >= endExclusive) return emptyList()
        return list.subList(start, endExclusive).toList()
    }

    fun canAnswer(request: KLineLoadRequest): Boolean {
        val now = clock()
        val key = KLineCacheKey(request.symbol, request.period)
        evictExpired(now)
        val entry = entries[key] ?: return false
        bumpToMru(entry, now)
        if (entry.bars.isEmpty()) return false
        return when (request.direction) {
            KLineLoadDirection.INITIAL -> entry.bars.size >= request.limit || !entry.hasMoreBefore
            KLineLoadDirection.BEFORE -> {
                val firstTs = entry.bars.first().timestamp
                val anchor = request.anchorTimestamp!!
                if (anchor <= firstTs) return !entry.hasMoreBefore
                firstIndexAtOrAfter(entry.bars, anchor - 1L) >= 0
            }
            KLineLoadDirection.AFTER -> {
                val lastTs = entry.bars.last().timestamp
                val anchor = request.anchorTimestamp!!
                if (anchor >= lastTs) return !entry.hasMoreAfter
                true
            }
        }
    }

    fun decorate(delegate: KLineDataSource): KLineDataSource = object : KLineDataSource {
        override fun load(request: KLineLoadRequest, callback: KLineLoadCallback): KLineCancelable {
            val key = KLineCacheKey(request.symbol, request.period)
            val cached = lookupPage(key, request)
            if (cached != null) {
                hits += 1
                callback.onResult(KLineLoadResult.Success(cached))
                return KLineCancelable {}
            }
            misses += 1
            return delegate.load(request) { result ->
                if (result is KLineLoadResult.Success) {
                    storePage(key, request, result.page)
                }
                callback.onResult(result)
            }
        }

        override fun subscribe(
            symbol: KLineSymbol,
            period: KLinePeriod,
            listener: KLineRealtimeListener,
        ): KLineCancelable {
            val key = KLineCacheKey(symbol, period)
            return delegate.subscribe(symbol, period) { event ->
                if (event is KLineRealtimeEvent.Bar) {
                    pushLiveBar(key, event.bar)
                }
                listener.onEvent(event)
            }
        }
    }

    internal fun lookupPage(key: KLineCacheKey, request: KLineLoadRequest): KLineLoadPage? {
        val now = clock()
        evictExpired(now)
        val entry = entries[key] ?: return null
        bumpToMru(entry, now)
        val bars = entry.bars
        if (bars.isEmpty()) return null
        return when (request.direction) {
            KLineLoadDirection.INITIAL -> {
                val slice = bars.takeLast(request.limit)
                KLineLoadPage(slice, hasMoreBefore = slice.size < bars.size || entry.hasMoreBefore, hasMoreAfter = entry.hasMoreAfter)
            }
            KLineLoadDirection.BEFORE -> {
                val anchor = request.anchorTimestamp!!
                val before = bars.filter { it.timestamp < anchor }.takeLast(request.limit)
                val hasMore = before.isEmpty() || (before.first().timestamp > bars.first().timestamp) || entry.hasMoreBefore
                if (before.isEmpty() && entry.hasMoreBefore) return null
                KLineLoadPage(before, hasMoreBefore = hasMore, hasMoreAfter = false)
            }
            KLineLoadDirection.AFTER -> {
                val anchor = request.anchorTimestamp!!
                val after = bars.filter { it.timestamp > anchor }.take(request.limit)
                val hasMore = after.isEmpty() || (after.last().timestamp < bars.last().timestamp) || entry.hasMoreAfter
                if (after.isEmpty() && entry.hasMoreAfter) return null
                KLineLoadPage(after, hasMoreBefore = false, hasMoreAfter = hasMore)
            }
        }
    }

    internal fun storePage(key: KLineCacheKey, request: KLineLoadRequest, page: KLineLoadPage) {
        val now = clock()
        val entry = getOrCreate(key, now)
        mergeBars(entry, page.bars)
        when (request.direction) {
            KLineLoadDirection.INITIAL -> {
                entry.hasMoreBefore = page.hasMoreBefore
                entry.hasMoreAfter = page.hasMoreAfter
            }
            KLineLoadDirection.BEFORE -> entry.hasMoreBefore = page.hasMoreBefore
            KLineLoadDirection.AFTER -> entry.hasMoreAfter = page.hasMoreAfter
        }
        enforceCap()
        bumpToMru(entry, now)
    }

    internal fun pushLiveBar(key: KLineCacheKey, bar: KLineBar) {
        val now = clock()
        val entry = entries[key]
        if (entry != null) {
            mergeBars(entry, listOf(bar))
            enforceCap()
            bumpToMru(entry, now)
        }
    }

    private fun bumpToMru(entry: KLineCacheEntry, now: Long) {
        entry.touch(now)
        entries.remove(entry.key)
        entries[entry.key] = entry
    }

    private fun getOrCreate(key: KLineCacheKey, now: Long): KLineCacheEntry {
        val existing = entries[key]
        if (existing != null) return existing
        if (entries.size >= maxEntries) {
            val iter = entries.values.iterator()
            if (iter.hasNext()) { iter.next(); iter.remove(); evictions += 1 }
        }
        val entry = KLineCacheEntry(key, defaultTtlMillis, createdAt = now)
        entries[key] = entry
        return entry
    }

    private fun mergeBars(entry: KLineCacheEntry, incoming: List<KLineBar>) {
        if (incoming.isEmpty()) return
        val map = LinkedHashMap<Long, KLineBar>(entry.bars.size + incoming.size)
        entry.bars.forEach { map[it.timestamp] = it }
        incoming.forEach { map[it.timestamp] = it }
        val merged = map.values.toMutableList()
        merged.sortBy { it.timestamp }
        val over = merged.size - maxBarsPerEntry
        if (over > 0) {
            repeat(over) { merged.removeFirst() }
            entry.hasMoreBefore = true
        }
        entry.bars.clear()
        entry.bars.addAll(merged)
    }

    private fun enforceCap() {
        if (entries.size <= maxEntries) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && entries.size > maxEntries) {
            iterator.next()
            iterator.remove()
            evictions += 1
        }
    }

    private fun evictExpired(now: Long) {
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next().value
            if (e.isExpired(now)) { iter.remove(); evictions += 1 }
        }
    }

    companion object {
        fun create(
            maxEntries: Int = 64,
            maxBarsPerEntry: Int = 10_000,
            ttlMillis: Long = 120_000L,
            clock: () -> Long,
        ): KLineDataCache = KLineDataCache(maxEntries, maxBarsPerEntry, ttlMillis, clock)
    }
}

private fun firstIndexAtOrAfter(bars: List<KLineBar>, timestamp: Long): Int {
    if (bars.isEmpty()) return 0
    var lo = 0
    var hi = bars.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (bars[mid].timestamp < timestamp) lo = mid + 1 else hi = mid
    }
    return lo
}
