package com.tencent.kuiklybase.kline.viewport

internal object KLineViewportEngine {
    fun initial(
        dataCount: Int,
        plotWidth: Double,
        config: KLineViewportConfig = KLineViewportConfig(),
        rightOffset: Double = 0.0,
    ): KLineViewport {
        require(dataCount >= 0) { "Data count must be non-negative" }
        require(plotWidth.isFinite() && plotWidth >= 0.0) { "Plot width must be finite and non-negative" }
        require(rightOffset.isFinite()) { "Right offset must be finite" }
        val barSpace = config.defaultBarSpace
        val lastIndex = (dataCount - 1).coerceAtLeast(0).toDouble()
        val resolvedOffset = rightOffset.coerceIn(0.0, config.maxRightOffsetBars)
        val endIndex = lastIndex + resolvedOffset
        return KLineViewport(
            startIndex = endIndex - visibleSpan(plotWidth, barSpace),
            endIndex = endIndex,
            barSpace = barSpace,
            rightOffset = resolvedOffset,
        )
    }

    fun panByPixels(
        viewport: KLineViewport,
        pixelDelta: Double,
        dataCount: Int,
        plotWidth: Double,
        config: KLineViewportConfig = KLineViewportConfig(),
    ): KLineViewport {
        if (!pixelDelta.isFinite()) return viewport
        val requestedEnd = viewport.endIndex - pixelDelta / viewport.barSpace
        return resolveEnd(
            requestedEnd = requestedEnd,
            barSpace = viewport.barSpace,
            dataCount = dataCount,
            plotWidth = plotWidth,
            config = config,
            zoomAnchor = null,
        )
    }

    fun zoomAtPixel(
        viewport: KLineViewport,
        factor: Double,
        focalPixel: Double,
        plotLeft: Double,
        plotWidth: Double,
        dataCount: Int,
        config: KLineViewportConfig = KLineViewportConfig(),
    ): KLineViewport {
        if (!factor.isFinite() || factor <= 0.0 || !focalPixel.isFinite() || !plotLeft.isFinite()) {
            return viewport
        }
        val focalOffset = (focalPixel - plotLeft).coerceIn(0.0, plotWidth.coerceAtLeast(0.0))
        val anchorIndex = viewport.startIndex + focalOffset / viewport.barSpace
        val nextBarSpace = (viewport.barSpace * factor).coerceIn(config.minBarSpace, config.maxBarSpace)
        val requestedStart = anchorIndex - focalOffset / nextBarSpace
        val requestedEnd = requestedStart + visibleSpan(plotWidth, nextBarSpace)
        return resolveEnd(
            requestedEnd = requestedEnd,
            barSpace = nextBarSpace,
            dataCount = dataCount,
            plotWidth = plotWidth,
            config = config,
            zoomAnchor = anchorIndex,
        )
    }

    fun preserveAfterPrepend(
        viewport: KLineViewport,
        insertedCount: Int,
    ): KLineViewport {
        require(insertedCount >= 0) { "Inserted count must be non-negative" }
        if (insertedCount == 0) return viewport
        val shift = insertedCount.toDouble()
        return viewport.copy(
            startIndex = viewport.startIndex + shift,
            endIndex = viewport.endIndex + shift,
            zoomAnchor = viewport.zoomAnchor?.plus(shift),
        )
    }

    fun scrollToLatest(
        viewport: KLineViewport,
        dataCount: Int,
        plotWidth: Double,
        config: KLineViewportConfig = KLineViewportConfig(),
    ): KLineViewport = resolveEnd(
        requestedEnd = (dataCount - 1).coerceAtLeast(0).toDouble(),
        barSpace = viewport.barSpace,
        dataCount = dataCount,
        plotWidth = plotWidth,
        config = config,
        zoomAnchor = null,
    )

    fun scrollToIndex(
        viewport: KLineViewport,
        index: Double,
        anchorRatio: Double,
        dataCount: Int,
        plotWidth: Double,
        config: KLineViewportConfig = KLineViewportConfig(),
    ): KLineViewport {
        if (!index.isFinite() || !anchorRatio.isFinite()) return viewport
        val span = visibleSpan(plotWidth, viewport.barSpace)
        val requestedEnd = index + span * (1.0 - anchorRatio.coerceIn(0.0, 1.0))
        return resolveEnd(
            requestedEnd = requestedEnd,
            barSpace = viewport.barSpace,
            dataCount = dataCount,
            plotWidth = plotWidth,
            config = config,
            zoomAnchor = index,
        )
    }

    fun resize(
        viewport: KLineViewport,
        dataCount: Int,
        plotWidth: Double,
        config: KLineViewportConfig = KLineViewportConfig(),
    ): KLineViewport = resolveEnd(
        requestedEnd = viewport.endIndex,
        barSpace = viewport.barSpace,
        dataCount = dataCount,
        plotWidth = plotWidth,
        config = config,
        zoomAnchor = viewport.zoomAnchor,
    )

    private fun resolveEnd(
        requestedEnd: Double,
        barSpace: Double,
        dataCount: Int,
        plotWidth: Double,
        config: KLineViewportConfig,
        zoomAnchor: Double?,
    ): KLineViewport {
        require(dataCount >= 0) { "Data count must be non-negative" }
        require(plotWidth.isFinite() && plotWidth >= 0.0) { "Plot width must be finite and non-negative" }
        val span = visibleSpan(plotWidth, barSpace)
        val lastIndex = (dataCount - 1).coerceAtLeast(0).toDouble()
        val minimumEnd = if (lastIndex >= span) span else lastIndex
        val maximumEnd = lastIndex + config.maxRightOffsetBars
        val endIndex = requestedEnd.coerceIn(minimumEnd, maximumEnd)
        return KLineViewport(
            startIndex = endIndex - span,
            endIndex = endIndex,
            barSpace = barSpace,
            rightOffset = endIndex - lastIndex,
            zoomAnchor = zoomAnchor,
        )
    }

    private fun visibleSpan(plotWidth: Double, barSpace: Double): Double =
        plotWidth.coerceAtLeast(0.0) / barSpace
}
