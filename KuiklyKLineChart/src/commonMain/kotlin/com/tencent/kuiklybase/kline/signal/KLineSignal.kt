package com.tencent.kuiklybase.kline.signal

import com.tencent.kuiklybase.kline.axis.KLineYCoordinateSystem
import com.tencent.kuiklybase.kline.interaction.KLineOverlayHitTester
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.overlay.KLineOverlayEngine
import com.tencent.kuiklybase.kline.overlay.KLineOverlayFigureStyle
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayPoint
import com.tencent.kuiklybase.kline.overlay.KLineOverlayStyleKey
import com.tencent.kuiklybase.kline.viewport.KLineXCoordinateSystem

enum class KLineSignalType { BUY, SELL, RISK, INFO }

data class KLineSignal(
    val id: String,
    val timestamp: Long,
    val value: Double,
    val type: KLineSignalType,
    val title: String,
    val summary: String,
    val confidence: Double? = null,
) {
    init {
        require(id.isNotBlank()) { "Signal id must not be blank" }
        require(value.isFinite()) { "Signal value must be finite" }
        require(title.isNotBlank()) { "Signal title must not be blank" }
        require(summary.isNotBlank()) { "Signal summary must not be blank" }
        require(confidence == null || confidence.isFinite()) { "Signal confidence must be finite" }
        require(confidence == null || confidence in 0.0..1.0) { "Signal confidence must be between 0 and 1" }
    }
}

class KLineSignalSet(signals: List<KLineSignal>) {
    val signals: List<KLineSignal> = SignalList(signals)
    private val byOverlayId: Map<String, KLineSignal>

    init {
        require(this.signals.map(KLineSignal::id).distinct().size == this.signals.size) {
            "Signal ids must be unique"
        }
        byOverlayId = this.signals.associateBy { overlayId(it.id) }
    }

    fun toOverlayInstances(paneId: String = "price"): List<KLineOverlayInstance> = SignalList(signals.map { signal ->
        KLineOverlayInstance(
            id = overlayId(signal.id),
            templateName = KLineBuiltInOverlays.TEXT_ANNOTATION_NAME,
            groupId = SIGNAL_GROUP_ID,
            paneId = paneId,
            points = listOf(KLineOverlayPoint(signal.timestamp, signal.value)),
            locked = true,
            styles = mapOf(KLineOverlayStyleKey.TEXT to signalStyle(signal.type)),
            extendData = mapOf("text" to signal.title, "signalId" to signal.id),
        )
    })

    fun hitTest(
        overlayInstances: List<KLineOverlayInstance>,
        paneId: String,
        pixelX: Double,
        pixelY: Double,
        xCoordinates: KLineXCoordinateSystem,
        yCoordinates: KLineYCoordinateSystem,
        hitRadius: Double = 6.0,
    ): KLineSignal? {
        val hit = KLineOverlayHitTester(KLineOverlayEngine(KLineExtensionRegistry.default())).hitTest(
            instances = overlayInstances.filter { it.groupId == SIGNAL_GROUP_ID && it.paneId == paneId },
            pixelX = pixelX,
            pixelY = pixelY,
            xCoordinates = xCoordinates,
            yCoordinates = yCoordinates,
            hitRadius = hitRadius,
        ) ?: return null
        return byOverlayId[hit.instanceId]
    }

    companion object {
        val EMPTY = KLineSignalSet(emptyList())
        internal const val SIGNAL_GROUP_ID = "kline-signals"
        internal fun overlayId(signalId: String) = "signal:$signalId"

        private fun signalStyle(type: KLineSignalType) = KLineOverlayFigureStyle(
            color = when (type) {
                KLineSignalType.BUY -> "#16A34A"
                KLineSignalType.SELL -> "#DC2626"
                KLineSignalType.RISK -> "#F59E0B"
                KLineSignalType.INFO -> "#2F80ED"
            },
        )
    }
}

private class SignalList<T>(source: Collection<T>) : AbstractList<T>() {
    private val values: Array<Any?> = source.map { it as Any? }.toTypedArray()
    override val size: Int get() = values.size
    @Suppress("UNCHECKED_CAST") override fun get(index: Int): T = values[index] as T
}
