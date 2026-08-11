package com.tencent.kuiklybase.kline.data

class StaticKLineDataSource(
    bars: List<KLineBar>,
) : KLineDataSource {
    private val bars: List<KLineBar> = bars
        .associateBy(KLineBar::timestamp)
        .values
        .sortedBy(KLineBar::timestamp)

    override fun load(
        request: KLineLoadRequest,
        callback: KLineLoadCallback,
    ): KLineCancelable {
        val page = when (request.direction) {
            KLineLoadDirection.INITIAL -> {
                val pageBars = bars.takeLast(request.limit)
                KLineLoadPage(
                    bars = pageBars,
                    hasMoreBefore = bars.size > pageBars.size,
                    hasMoreAfter = false,
                )
            }

            KLineLoadDirection.BEFORE -> {
                val candidates = bars.filter { it.timestamp < requireNotNull(request.anchorTimestamp) }
                val pageBars = candidates.takeLast(request.limit)
                KLineLoadPage(
                    bars = pageBars,
                    hasMoreBefore = candidates.size > pageBars.size,
                    hasMoreAfter = bars.any { it.timestamp >= requireNotNull(request.anchorTimestamp) },
                )
            }

            KLineLoadDirection.AFTER -> {
                val candidates = bars.filter { it.timestamp > requireNotNull(request.anchorTimestamp) }
                val pageBars = candidates.take(request.limit)
                KLineLoadPage(
                    bars = pageBars,
                    hasMoreBefore = bars.any { it.timestamp <= requireNotNull(request.anchorTimestamp) },
                    hasMoreAfter = candidates.size > pageBars.size,
                )
            }
        }
        callback.onResult(KLineLoadResult.Success(page))
        return NoOpCancelable
    }

    override fun subscribe(
        symbol: KLineSymbol,
        period: KLinePeriod,
        listener: KLineRealtimeListener,
    ): KLineCancelable = NoOpCancelable

    private object NoOpCancelable : KLineCancelable {
        override fun cancel() = Unit
    }
}
