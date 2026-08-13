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
import com.tencent.kuiklybase.kline.render.KLineOverlayRenderFigure

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
        hitRadius: Double = 18.0,
    ): KLineSignal? {
        val textHit = overlayInstances.asReversed().firstNotNullOfOrNull { instance ->
            if (instance.groupId != SIGNAL_GROUP_ID || instance.paneId != paneId) return@firstNotNullOfOrNull null
            val signal = byOverlayId[instance.id] ?: return@firstNotNullOfOrNull null
            val point = instance.points.firstOrNull() ?: return@firstNotNullOfOrNull null
            val anchorX = xCoordinates.timestampToPixel(point.timestamp) ?: return@firstNotNullOfOrNull null
            val anchorY = yCoordinates.valueToPixel(point.value)
            val width = signal.title.length * 12.0 * 0.6
            if (pixelX in (anchorX - hitRadius)..(anchorX + width + hitRadius) &&
                pixelY in (anchorY - hitRadius)..(anchorY + 12.0 + hitRadius)) signal else null
        }
        if (textHit != null) return textHit
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

    /** Hit-tests the exact immutable figures consumed by the renderer. */
    fun hitTestRendered(
        figures: List<KLineOverlayRenderFigure>,
        pixelX: Double,
        pixelY: Double,
        hitRadius: Double = 18.0,
    ): KLineSignal? = figures.asReversed().firstNotNullOfOrNull { figure ->
        val text = figure as? KLineOverlayRenderFigure.Text ?: return@firstNotNullOfOrNull null
        val width = text.text.length * text.style.textSize * 0.6
        if (pixelX !in (text.anchor.x - hitRadius)..(text.anchor.x + width + hitRadius) ||
            pixelY !in (text.anchor.y - hitRadius)..(text.anchor.y + text.style.textSize + hitRadius)
        ) return@firstNotNullOfOrNull null
        signals.firstOrNull { it.title == text.text }
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
