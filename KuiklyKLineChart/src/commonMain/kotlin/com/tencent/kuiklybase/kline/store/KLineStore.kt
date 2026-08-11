package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineBarValidator
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorEngine
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorResult
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.viewport.KLineViewport

data class KLineStoreSnapshot(
    val symbol: KLineSymbol? = null,
    val period: KLinePeriod? = null,
    val bars: List<KLineBar> = emptyList(),
    val hasMoreBefore: Boolean = false,
    val hasMoreAfter: Boolean = false,
    val loadState: KLineLoadState = KLineLoadState(),
    val viewport: KLineViewport? = null,
    val panes: List<KLinePane> = emptyList(),
    val indicatorInstances: List<KLineIndicatorInstance> = emptyList(),
    val indicatorResults: Map<String, KLineIndicatorResult> = emptyMap(),
    val overlayInstances: List<KLineOverlayInstance> = emptyList(),
    val selectedOverlayId: String? = null,
    val dataRevision: Long = 0,
    val viewportRevision: Long = 0,
    val paneRevision: Long = 0,
    val indicatorRevision: Long = 0,
    val overlayRevision: Long = 0,
)

enum class KLineLoadPhase {
    IDLE,
    LOADING,
    FAILED,
}

data class KLineLoadState(
    val initial: KLineLoadPhase = KLineLoadPhase.IDLE,
    val before: KLineLoadPhase = KLineLoadPhase.IDLE,
    val after: KLineLoadPhase = KLineLoadPhase.IDLE,
)

class KLineStore(
    private val onError: (KLineError) -> Unit = {},
    val extensionRegistry: KLineExtensionRegistry = KLineBuiltInIndicators.registry(),
) {
    private val observers = mutableListOf<(KLineStoreSnapshot, KLineStoreSnapshot) -> Unit>()
    private val indicatorEngine = KLineIndicatorEngine(extensionRegistry)

    var snapshot: KLineStoreSnapshot = KLineStoreSnapshot()
        private set

    internal fun observe(
        observer: (previous: KLineStoreSnapshot, current: KLineStoreSnapshot) -> Unit,
    ): KLineStoreSubscription {
        observers += observer
        return KLineStoreSubscription { observers.remove(observer) }
    }

    internal fun reset(
        symbol: KLineSymbol,
        period: KLinePeriod,
    ) {
        publishData(snapshot.copy(
            symbol = symbol,
            period = period,
            bars = emptyList(),
            hasMoreBefore = false,
            hasMoreAfter = false,
            loadState = KLineLoadState(),
            viewport = null,
            dataRevision = snapshot.dataRevision + 1,
            viewportRevision = snapshot.viewportRevision + 1,
        ))
    }

    internal fun setLoadPhase(
        direction: KLineLoadDirection,
        phase: KLineLoadPhase,
    ) {
        val state = snapshot.loadState
        publish(snapshot.copy(
            loadState = when (direction) {
                KLineLoadDirection.INITIAL -> state.copy(initial = phase)
                KLineLoadDirection.BEFORE -> state.copy(before = phase)
                KLineLoadDirection.AFTER -> state.copy(after = phase)
            },
        ))
    }

    internal fun onLoadFailure(
        direction: KLineLoadDirection,
        message: String,
    ) {
        setLoadPhase(direction, KLineLoadPhase.FAILED)
        onError(
            KLineError(
                code = if (direction == KLineLoadDirection.INITIAL) {
                    KLineErrorCode.INITIAL_LOAD_FAILED
                } else {
                    KLineErrorCode.HISTORY_LOAD_FAILED
                },
                message = message,
            ),
        )
    }

    internal fun onRealtimeFailure(message: String) {
        onError(
            KLineError(
                code = KLineErrorCode.REALTIME_SUBSCRIPTION_FAILED,
                message = message,
            ),
        )
    }

    fun replaceAll(
        bars: List<KLineBar>,
        hasMoreBefore: Boolean = false,
        hasMoreAfter: Boolean = false,
    ) {
        val normalized = normalize(bars)
        publishData(snapshot.copy(
            bars = normalized,
            hasMoreBefore = hasMoreBefore,
            hasMoreAfter = hasMoreAfter,
            dataRevision = snapshot.dataRevision + 1,
        ))
    }

    fun prepend(
        bars: List<KLineBar>,
        hasMoreBefore: Boolean,
    ): Int {
        val normalized = normalize(bars)
        val previousFirstTimestamp = snapshot.bars.firstOrNull()?.timestamp
        val existingTimestamps = snapshot.bars.mapTo(mutableSetOf(), KLineBar::timestamp)
        val insertedBefore = normalized.count { bar ->
            bar.timestamp !in existingTimestamps &&
                (previousFirstTimestamp == null || bar.timestamp < previousFirstTimestamp)
        }
        publishData(snapshot.copy(
            bars = merge(normalized, snapshot.bars),
            hasMoreBefore = hasMoreBefore,
            dataRevision = snapshot.dataRevision + 1,
        ))
        return insertedBefore
    }

    fun append(
        bars: List<KLineBar>,
        hasMoreAfter: Boolean,
    ) {
        publishData(snapshot.copy(
            bars = merge(snapshot.bars, normalize(bars)),
            hasMoreAfter = hasMoreAfter,
            dataRevision = snapshot.dataRevision + 1,
        ))
    }

    fun applyRealtime(bar: KLineBar) {
        if (!accept(bar)) return
        val tail = snapshot.bars.lastOrNull()
        val updatedBars = when {
            tail == null -> listOf(bar)
            bar.timestamp == tail.timestamp -> snapshot.bars.dropLast(1) + bar
            bar.timestamp > tail.timestamp -> snapshot.bars + bar
            else -> return
        }
        publishData(snapshot.copy(
            bars = updatedBars,
            dataRevision = snapshot.dataRevision + 1,
        ))
    }

    internal fun setViewport(viewport: KLineViewport?) {
        if (snapshot.viewport == viewport) return
        publish(snapshot.copy(
            viewport = viewport,
            viewportRevision = snapshot.viewportRevision + 1,
        ))
    }

    internal fun setPanes(panes: List<KLinePane>) {
        require(panes.map(KLinePane::id).distinct().size == panes.size) { "Pane ids must be unique" }
        if (snapshot.panes == panes) return
        publish(snapshot.copy(
            panes = panes.toList(),
            paneRevision = snapshot.paneRevision + 1,
        ))
    }

    internal fun setIndicator(instance: KLineIndicatorInstance) {
        val instances = snapshot.indicatorInstances.toMutableList()
        val index = instances.indexOfFirst { it.id == instance.id }
        if (index >= 0) {
            if (instances[index] == instance) return
            instances[index] = instance
        } else {
            instances += instance
        }
        val results = calculateIndicators(instances, snapshot.bars, snapshot.dataRevision)
        publish(snapshot.copy(
            indicatorInstances = instances,
            indicatorResults = results,
            indicatorRevision = snapshot.indicatorRevision + 1,
        ))
    }

    internal fun removeIndicator(instanceId: String) {
        if (snapshot.indicatorInstances.none { it.id == instanceId }) return
        val instances = snapshot.indicatorInstances.filterNot { it.id == instanceId }
        publish(snapshot.copy(
            indicatorInstances = instances,
            indicatorResults = calculateIndicators(instances, snapshot.bars, snapshot.dataRevision),
            indicatorRevision = snapshot.indicatorRevision + 1,
        ))
    }

    internal fun addOverlay(instance: KLineOverlayInstance) {
        require(snapshot.overlayInstances.none { it.id == instance.id }) {
            "Overlay instance id already exists: ${instance.id}"
        }
        publish(snapshot.copy(
            overlayInstances = normalizeOverlayOrder(snapshot.overlayInstances + instance.immutableCopy()),
            overlayRevision = snapshot.overlayRevision + 1,
        ))
    }

    internal fun updateOverlay(instance: KLineOverlayInstance) {
        val index = snapshot.overlayInstances.indexOfFirst { it.id == instance.id }
        require(index >= 0) { "Unknown overlay instance: ${instance.id}" }
        val immutable = instance.immutableCopy()
        if (snapshot.overlayInstances[index] == immutable) return
        val instances = snapshot.overlayInstances.toMutableList()
        instances[index] = immutable
        publish(snapshot.copy(
            overlayInstances = normalizeOverlayOrder(instances),
            overlayRevision = snapshot.overlayRevision + 1,
        ))
    }

    internal fun removeOverlay(instanceId: String) {
        if (snapshot.overlayInstances.none { it.id == instanceId }) return
        publish(snapshot.copy(
            overlayInstances = snapshot.overlayInstances.filterNot { it.id == instanceId },
            selectedOverlayId = snapshot.selectedOverlayId.takeUnless { it == instanceId },
            overlayRevision = snapshot.overlayRevision + 1,
        ))
    }

    internal fun selectOverlay(instanceId: String?) {
        require(instanceId == null || snapshot.overlayInstances.any { it.id == instanceId }) {
            "Unknown overlay instance: $instanceId"
        }
        if (snapshot.selectedOverlayId == instanceId) return
        publish(snapshot.copy(
            selectedOverlayId = instanceId,
            overlayRevision = snapshot.overlayRevision + 1,
        ))
    }

    internal fun removeOverlayGroup(groupId: String) {
        require(groupId.isNotBlank()) { "Overlay group id must not be blank" }
        val removedIds = snapshot.overlayInstances
            .filter { it.groupId == groupId }
            .mapTo(mutableSetOf(), KLineOverlayInstance::id)
        if (removedIds.isEmpty()) return
        publish(snapshot.copy(
            overlayInstances = snapshot.overlayInstances.filterNot { it.id in removedIds },
            selectedOverlayId = snapshot.selectedOverlayId.takeUnless { it in removedIds },
            overlayRevision = snapshot.overlayRevision + 1,
        ))
    }

    private fun accept(bar: KLineBar): Boolean {
        val reason = KLineBarValidator.invalidReason(bar) ?: return true
        onError(
            KLineError(
                code = KLineErrorCode.INVALID_DATA,
                message = reason,
                timestamp = bar.timestamp,
            ),
        )
        return false
    }

    private fun normalize(bars: List<KLineBar>): List<KLineBar> = bars
        .filter(::accept)
        .associateBy(KLineBar::timestamp)
        .values
        .sortedBy(KLineBar::timestamp)

    private fun merge(
        first: List<KLineBar>,
        second: List<KLineBar>,
    ): List<KLineBar> = (first + second)
        .associateBy(KLineBar::timestamp)
        .values
        .sortedBy(KLineBar::timestamp)

    private fun publishData(next: KLineStoreSnapshot) {
        val results = calculateIndicators(next.indicatorInstances, next.bars, next.dataRevision)
        publish(next.copy(
            indicatorResults = results,
            indicatorRevision = if (next.indicatorInstances.isEmpty() && snapshot.indicatorInstances.isEmpty()) {
                snapshot.indicatorRevision
            } else {
                snapshot.indicatorRevision + 1
            },
        ))
    }

    private fun calculateIndicators(
        instances: List<KLineIndicatorInstance>,
        bars: List<KLineBar>,
        dataRevision: Long,
    ): Map<String, KLineIndicatorResult> = instances
        .asSequence()
        .filter(KLineIndicatorInstance::visible)
        .associate { instance ->
            instance.id to indicatorEngine.calculate(instance, bars, dataRevision)
        }

    private fun publish(next: KLineStoreSnapshot) {
        val previous = snapshot
        if (previous == next) return
        snapshot = next
        observers.toList().forEach { it(previous, next) }
    }

    private fun normalizeOverlayOrder(instances: List<KLineOverlayInstance>): List<KLineOverlayInstance> =
        instances.sortedBy(KLineOverlayInstance::zIndex)
}

private fun KLineOverlayInstance.immutableCopy(): KLineOverlayInstance = copy(
    points = points.toList(),
    styles = styles.mapValues { (_, style) -> style.copy(lineDash = style.lineDash.toList()) },
    extendData = extendData.toMap(),
)

internal fun interface KLineStoreSubscription {
    fun cancel()
}
