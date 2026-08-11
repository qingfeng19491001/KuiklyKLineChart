package com.tencent.kuiklybase.kline.store

import com.tencent.kuiklybase.kline.config.KLineTheme
import com.tencent.kuiklybase.kline.controller.KLineChartState
import com.tencent.kuiklybase.kline.controller.deepCopy
import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.data.KLineBarValidator
import com.tencent.kuiklybase.kline.data.KLineLoadDirection
import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.PersistentKLineBarList
import com.tencent.kuiklybase.kline.data.emptyPersistentKLineBars
import com.tencent.kuiklybase.kline.data.persistentKLineBars
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.data.exactTimestampIndex
import com.tencent.kuiklybase.kline.error.KLineError
import com.tencent.kuiklybase.kline.error.KLineErrorCode
import com.tencent.kuiklybase.kline.format.KLineFormatters
import com.tencent.kuiklybase.kline.indicator.KLineBuiltInIndicators
import com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorEngine
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorCalculation
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorResult
import com.tencent.kuiklybase.kline.indicator.KLineDataChange
import com.tencent.kuiklybase.kline.indicator.KLineDataChangeKind
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorUpdateContext
import com.tencent.kuiklybase.kline.indicator.KLinePerformanceKey
import com.tencent.kuiklybase.kline.indicator.KLinePerformanceTracker
import com.tencent.kuiklybase.kline.indicator.TrackedKLineBars
import com.tencent.kuiklybase.kline.interaction.KLineBarSelection
import com.tencent.kuiklybase.kline.interaction.KLineCrosshair
import com.tencent.kuiklybase.kline.interaction.KLineInteractionSession
import com.tencent.kuiklybase.kline.interaction.KLineInteractionState
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.viewport.KLineViewport
import kotlin.coroutines.cancellation.CancellationException

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
    val interactionState: KLineInteractionState = KLineInteractionState.IDLE,
    val interactionSession: KLineInteractionSession? = null,
    val crosshair: KLineCrosshair? = null,
    val clickSelection: KLineBarSelection? = null,
    val dataRevision: Long = 0,
    val viewportRevision: Long = 0,
    val paneRevision: Long = 0,
    val indicatorRevision: Long = 0,
    val overlayRevision: Long = 0,
    val interactionRevision: Long = 0,
    val theme: KLineTheme = KLineTheme.LIGHT,
    val styleRevision: Long = 0,
    val formatters: KLineFormatters = KLineFormatters.DEFAULT,
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
    val extensionRegistry: KLineExtensionRegistry = KLineExtensionRegistry(
        templates = KLineBuiltInIndicators.templates,
        overlayTemplates = KLineBuiltInOverlays.templates,
    ),
) {
    private val observers = mutableListOf<(KLineStoreSnapshot, KLineStoreSnapshot) -> Unit>()
    private val errorObservers = mutableListOf<(KLineError) -> Unit>()
    private val indicatorEngine = KLineIndicatorEngine(extensionRegistry)
    private var performanceTracker: KLinePerformanceTracker? = null

    var snapshot: KLineStoreSnapshot = KLineStoreSnapshot()
        private set

    internal fun trackPerformance(tracker: KLinePerformanceTracker?) {
        performanceTracker = tracker
    }

    internal fun observe(
        observer: (previous: KLineStoreSnapshot, current: KLineStoreSnapshot) -> Unit,
    ): KLineStoreSubscription {
        observers += observer
        return KLineStoreSubscription { observers.remove(observer) }
    }

    fun observeErrors(observer: (KLineError) -> Unit): KLineErrorSubscription {
        errorObservers += observer
        return KLineErrorSubscription { errorObservers.remove(observer) }
    }

    internal fun reset(
        symbol: KLineSymbol,
        period: KLinePeriod,
    ) {
        publishData(snapshot.copy(
            symbol = symbol,
            period = period,
            bars = emptyPersistentKLineBars(),
            hasMoreBefore = false,
            hasMoreAfter = false,
            loadState = KLineLoadState(),
            viewport = null,
            selectedOverlayId = null,
            interactionState = KLineInteractionState.IDLE,
            interactionSession = null,
            crosshair = null,
            clickSelection = null,
            dataRevision = snapshot.dataRevision + 1,
            viewportRevision = snapshot.viewportRevision + 1,
            interactionRevision = snapshot.interactionRevision + 1,
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
        reportError(
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
        reportError(
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
        updateData(normalized, hasMoreBefore = hasMoreBefore, hasMoreAfter = hasMoreAfter, change = fullChange(snapshot.bars.size, normalized.size))
    }

    fun prepend(
        bars: List<KLineBar>,
        hasMoreBefore: Boolean,
    ): Int {
        val normalized = normalize(bars)
        val existing = persistentBars(snapshot.bars)
        if (normalized.isEmpty()) {
            updateData(existing, hasMoreBefore = hasMoreBefore, change = fullChange(existing.size, existing.size), knownChanged = false)
            return 0
        }
        val purePrepend = existing.isNotEmpty() && normalized.isNotEmpty() &&
            requireNotNull(normalized.lastTimestamp) < requireNotNull(existing.firstTimestamp)
        val merged = if (purePrepend) {
            performanceTracker?.recordBarFastPathStep()
            MergeOutcome(
                existing.prepend(normalized, performanceTracker),
                KLineDataChange(KLineDataChangeKind.HEAD_PREPEND, 0 until 0, 0 until normalized.size, 0, snapshot.bars.size),
                changed = true,
                insertedBefore = normalized.size,
            )
        } else merge(existing, normalized, KLineDataChangeKind.HEAD_PREPEND)
        updateData(merged.bars, hasMoreBefore = hasMoreBefore, change = merged.change, knownChanged = merged.changed)
        return merged.insertedBefore
    }

    fun append(
        bars: List<KLineBar>,
        hasMoreAfter: Boolean,
    ) {
        val normalized = normalize(bars)
        val existing = persistentBars(snapshot.bars)
        if (normalized.isEmpty()) {
            updateData(existing, hasMoreAfter = hasMoreAfter, change = fullChange(existing.size, existing.size), knownChanged = false)
            return
        }
        val pureAppend = existing.isNotEmpty() && normalized.isNotEmpty() &&
            requireNotNull(normalized.firstTimestamp) > requireNotNull(existing.lastTimestamp)
        val merged = if (pureAppend) {
            performanceTracker?.recordBarFastPathStep()
            MergeOutcome(
                existing.concat(normalized, performanceTracker),
                KLineDataChange(KLineDataChangeKind.TAIL_APPEND, snapshot.bars.size until snapshot.bars.size, snapshot.bars.size until snapshot.bars.size + normalized.size, snapshot.bars.size, 0),
                changed = true,
                insertedBefore = 0,
            )
        } else merge(existing, normalized, KLineDataChangeKind.TAIL_APPEND)
        updateData(merged.bars, hasMoreAfter = hasMoreAfter, change = merged.change, knownChanged = merged.changed)
    }

    fun applyRealtime(bar: KLineBar) {
        if (!accept(bar)) return
        val existing = persistentBars(snapshot.bars)
        val tail = existing.lastTimestamp?.let { existing.get(existing.lastIndex, performanceTracker) }
        if (tail == bar) return
        val updatedBars = when {
            tail == null -> persistentKLineBars(listOf(bar), performanceTracker)
            bar.timestamp == tail.timestamp -> existing.replaceRange(existing.lastIndex, existing.size, listOf(bar), performanceTracker)
            bar.timestamp > tail.timestamp -> existing.concat(listOf(bar), performanceTracker)
            else -> return
        }
        performanceTracker?.recordBarFastPathStep()
        val oldSize = snapshot.bars.size
        val change = if (tail?.timestamp == bar.timestamp) {
            KLineDataChange(KLineDataChangeKind.TAIL_UPDATE, oldSize - 1 until oldSize, oldSize - 1 until oldSize, oldSize - 1, 0)
        } else {
            KLineDataChange(KLineDataChangeKind.TAIL_APPEND, oldSize until oldSize, oldSize until oldSize + 1, oldSize, 0)
        }
        updateData(updatedBars, change = change, knownChanged = true)
    }

    internal fun setViewport(viewport: KLineViewport?) {
        if (snapshot.viewport == viewport) return
        publish(snapshot.copy(
            viewport = viewport,
            viewportRevision = snapshot.viewportRevision + 1,
        ))
    }

    internal fun setTheme(theme: KLineTheme) {
        if (snapshot.theme == theme) return
        publish(snapshot.copy(theme = theme, styleRevision = snapshot.styleRevision + 1))
    }

    internal fun setFormatters(formatters: KLineFormatters) {
        if (snapshot.formatters == formatters) return
        publish(snapshot.copy(formatters = formatters, styleRevision = snapshot.styleRevision + 1))
    }

    internal fun validateRestoreState(state: KLineChartState) {
        require(state.panes.map(KLinePane::id).distinct().size == state.panes.size) { "Pane ids must be unique" }
        val knownIndicators = state.indicatorInstances.filter { extensionRegistry.find(it.templateName) != null }
        val knownOverlays = state.overlayInstances.filter { extensionRegistry.findOverlay(it.templateName) != null }
        require(knownIndicators.map(KLineIndicatorInstance::id).distinct().size == knownIndicators.size) { "Indicator instance ids must be unique" }
        require(knownOverlays.map(KLineOverlayInstance::id).distinct().size == knownOverlays.size) { "Overlay instance ids must be unique" }
        val paneIds = state.panes.mapTo(mutableSetOf(), KLinePane::id)
        knownIndicators.forEach {
            require(it.paneId in paneIds) { "Indicator ${it.id} references missing pane ${it.paneId}" }
        }
        knownOverlays.forEach {
            require(it.paneId in paneIds) { "Overlay ${it.id} references missing pane ${it.paneId}" }
            val required = extensionRegistry.findOverlay(it.templateName)!!.requiredPointCount
            require(it.points.size >= required) { "Overlay ${it.id} requires at least $required points" }
        }
    }

    internal fun restoreState(state: KLineChartState) {
        validateRestoreState(state)
        val panes = state.panes.map { it.copy(yAxes = it.yAxes.toList()) }
        val indicators = state.indicatorInstances
            .filter { extensionRegistry.find(it.templateName) != null }
            .map { it.copy(params = it.params.toList()) }
        val overlays = state.overlayInstances
            .filter { extensionRegistry.findOverlay(it.templateName) != null }
            .map { it.deepCopy() }
            .sortedBy(KLineOverlayInstance::zIndex)
        val current = snapshot
        val indicatorResults = calculateIndicators(indicators, current.bars, current.dataRevision)
        val next = current.copy(
            viewport = state.viewport?.copy(),
            panes = panes,
            indicatorInstances = indicators,
            indicatorResults = indicatorResults,
            overlayInstances = overlays,
            selectedOverlayId = null,
            interactionState = KLineInteractionState.IDLE,
            interactionSession = null,
            crosshair = null,
            clickSelection = null,
            theme = state.theme,
            formatters = state.formatters,
            viewportRevision = current.viewportRevision + if (current.viewport != state.viewport) 1 else 0,
            paneRevision = current.paneRevision + if (current.panes != panes) 1 else 0,
            indicatorRevision = current.indicatorRevision + if (current.indicatorInstances != indicators || current.indicatorResults != indicatorResults) 1 else 0,
            overlayRevision = current.overlayRevision + if (current.overlayInstances != overlays || current.selectedOverlayId != null) 1 else 0,
            interactionRevision = current.interactionRevision + if (current.interactionState != KLineInteractionState.IDLE || current.interactionSession != null || current.crosshair != null || current.clickSelection != null) 1 else 0,
            styleRevision = current.styleRevision + if (current.theme != state.theme || current.formatters != state.formatters) 1 else 0,
        )
        publish(next)
    }

    internal fun reportRestoreFailure(cause: Exception) {
        reportError(KLineError(KLineErrorCode.STATE_RESTORE_FAILED, cause.message ?: "State restore failed"))
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
        val sessionReferencesRemoved = when (val session = snapshot.interactionSession) {
            is KLineInteractionSession.DraggingOverlay -> session.instanceId == instanceId
            is KLineInteractionSession.DraggingOverlayPoint -> session.instanceId == instanceId
            else -> false
        }
        val selectedRemoved = snapshot.selectedOverlayId == instanceId
        publish(snapshot.copy(
            overlayInstances = snapshot.overlayInstances.filterNot { it.id == instanceId },
            selectedOverlayId = snapshot.selectedOverlayId.takeUnless { it == instanceId },
            overlayRevision = snapshot.overlayRevision + 1,
            interactionState = if (sessionReferencesRemoved) KLineInteractionState.IDLE else snapshot.interactionState,
            interactionSession = if (sessionReferencesRemoved) null else snapshot.interactionSession,
            interactionRevision = if (sessionReferencesRemoved || selectedRemoved) snapshot.interactionRevision + 1 else snapshot.interactionRevision,
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
            interactionRevision = snapshot.interactionRevision + 1,
        ))
    }

    internal fun beginInteraction(session: KLineInteractionSession) {
        val immutable = session.immutableCopy()
        check(snapshot.interactionState == KLineInteractionState.IDLE) {
            "Cannot begin ${immutable.state} while ${snapshot.interactionState} is active"
        }
        publish(snapshot.copy(
            interactionState = immutable.state,
            interactionSession = immutable,
            crosshair = (immutable as? KLineInteractionSession.Crosshair)?.position ?: snapshot.crosshair,
            interactionRevision = snapshot.interactionRevision + 1,
        ))
    }

    internal fun updateInteraction(session: KLineInteractionSession) {
        val immutable = session.immutableCopy()
        check(snapshot.interactionState == immutable.state && snapshot.interactionSession != null) {
            "Cannot update ${immutable.state} while ${snapshot.interactionState} is active"
        }
        publish(snapshot.copy(
            interactionSession = immutable,
            crosshair = (immutable as? KLineInteractionSession.Crosshair)?.position ?: snapshot.crosshair,
            interactionRevision = snapshot.interactionRevision + 1,
        ))
    }

    internal fun cancelInteraction() {
        if (snapshot.interactionState == KLineInteractionState.IDLE) return
        val clearCrosshair = snapshot.interactionState == KLineInteractionState.CROSSHAIR
        publish(snapshot.copy(
            interactionState = KLineInteractionState.IDLE,
            interactionSession = null,
            crosshair = if (clearCrosshair) null else snapshot.crosshair,
            interactionRevision = snapshot.interactionRevision + 1,
        ))
    }

    internal fun finishInteraction() {
        if (snapshot.interactionState == KLineInteractionState.IDLE) return
        publish(snapshot.copy(
            interactionState = KLineInteractionState.IDLE,
            interactionSession = null,
            interactionRevision = snapshot.interactionRevision + 1,
        ))
    }

    internal fun clearCrosshair() {
        if (snapshot.crosshair == null && snapshot.interactionState != KLineInteractionState.CROSSHAIR) return
        publish(snapshot.copy(
            interactionState = if (snapshot.interactionState == KLineInteractionState.CROSSHAIR) KLineInteractionState.IDLE else snapshot.interactionState,
            interactionSession = snapshot.interactionSession?.takeUnless { it.state == KLineInteractionState.CROSSHAIR },
            crosshair = null,
            interactionRevision = snapshot.interactionRevision + 1,
        ))
    }

    internal fun resetInteractionState() {
        if (snapshot.interactionState == KLineInteractionState.IDLE && snapshot.interactionSession == null &&
            snapshot.crosshair == null && snapshot.clickSelection == null && snapshot.selectedOverlayId == null) return
        publish(snapshot.copy(
            interactionState = KLineInteractionState.IDLE,
            interactionSession = null,
            crosshair = null,
            clickSelection = null,
            selectedOverlayId = null,
            overlayRevision = snapshot.overlayRevision + if (snapshot.selectedOverlayId != null) 1 else 0,
            interactionRevision = snapshot.interactionRevision + 1,
        ))
    }

    internal fun setClickSelection(selection: KLineBarSelection?) {
        if (snapshot.clickSelection == selection) return
        publish(snapshot.copy(
            clickSelection = selection,
            interactionRevision = snapshot.interactionRevision + 1,
        ))
    }

    internal fun removeOverlayGroup(groupId: String) {
        require(groupId.isNotBlank()) { "Overlay group id must not be blank" }
        val removedIds = snapshot.overlayInstances
            .filter { it.groupId == groupId }
            .mapTo(mutableSetOf(), KLineOverlayInstance::id)
        if (removedIds.isEmpty()) return
        val selectedRemoved = snapshot.selectedOverlayId in removedIds
        val sessionReferencesRemoved = when (val session = snapshot.interactionSession) {
            is KLineInteractionSession.DraggingOverlay -> session.instanceId in removedIds
            is KLineInteractionSession.DraggingOverlayPoint -> session.instanceId in removedIds
            else -> false
        }
        publish(snapshot.copy(
            overlayInstances = snapshot.overlayInstances.filterNot { it.id in removedIds },
            selectedOverlayId = snapshot.selectedOverlayId.takeUnless { it in removedIds },
            overlayRevision = snapshot.overlayRevision + 1,
            interactionState = if (sessionReferencesRemoved) KLineInteractionState.IDLE else snapshot.interactionState,
            interactionSession = if (sessionReferencesRemoved) null else snapshot.interactionSession,
            interactionRevision = if (selectedRemoved || sessionReferencesRemoved) snapshot.interactionRevision + 1 else snapshot.interactionRevision,
        ))
    }

    private fun accept(bar: KLineBar): Boolean {
        val reason = KLineBarValidator.invalidReason(bar) ?: return true
        reportError(
            KLineError(
                code = KLineErrorCode.INVALID_DATA,
                message = reason,
                timestamp = bar.timestamp,
            ),
        )
        return false
    }

    private fun normalize(bars: List<KLineBar>): PersistentKLineBarList {
        val sorted = bars.withIndex().filter { accept(it.value) }
            .sortedWith(compareBy<IndexedValue<KLineBar>> { it.value.timestamp }.thenBy { it.index })
        val result = ArrayList<KLineBar>(sorted.size)
        sorted.forEach { indexed ->
            if (result.lastOrNull()?.timestamp == indexed.value.timestamp) result[result.lastIndex] = indexed.value
            else result += indexed.value
        }
        return persistentKLineBars(result, performanceTracker)
    }

    private fun persistentBars(bars: List<KLineBar>): PersistentKLineBarList =
        if (bars is PersistentKLineBarList) bars else persistentKLineBars(bars, null)

    private fun merge(
        first: List<KLineBar>,
        second: List<KLineBar>,
        preferredKind: KLineDataChangeKind,
    ): MergeOutcome {
        val result = ArrayList<KLineBar>(first.size + second.size)
        val leftIterator = first.iterator()
        val rightIterator = second.iterator()
        var left: KLineBar? = null
        var right: KLineBar? = null
        fun takeLeft(): KLineBar? {
            if (!leftIterator.hasNext()) return null
            performanceTracker?.recordBarOldItemRead()
            return leftIterator.next()
        }
        fun takeRight(): KLineBar? = if (rightIterator.hasNext()) rightIterator.next() else null
        left = takeLeft()
        right = takeRight()
        var prefix = 0
        var prefixOpen = true
        var trailingUnchanged = 0
        var insertedBefore = 0
        val previousFirstTimestamp = first.firstOrNull()?.timestamp
        fun emit(bar: KLineBar, unchangedExisting: Boolean) {
            if (prefixOpen && unchangedExisting) prefix++ else prefixOpen = false
            trailingUnchanged = if (unchangedExisting) trailingUnchanged + 1 else 0
            result += bar
        }
        while (left != null || right != null) {
            performanceTracker?.recordStoreMergeStep()
            when {
                right == null || left != null && left!!.timestamp < right!!.timestamp -> {
                    emit(left!!, true)
                    left = takeLeft()
                }
                left == null || right!!.timestamp < left!!.timestamp -> {
                    if (previousFirstTimestamp == null || right!!.timestamp < previousFirstTimestamp) insertedBefore++
                    emit(right!!, false)
                    right = takeRight()
                }
                else -> {
                    val unchanged = left == right
                    emit(right!!, unchanged)
                    left = takeLeft()
                    right = takeRight()
                }
            }
        }
        val kind = when {
            first.isEmpty() && result.isNotEmpty() -> preferredKind
            prefix == first.size && result.size > first.size -> KLineDataChangeKind.TAIL_APPEND
            trailingUnchanged == first.size && result.size > first.size -> KLineDataChangeKind.HEAD_PREPEND
            prefix == first.lastIndex && result.size == first.size -> KLineDataChangeKind.TAIL_UPDATE
            else -> KLineDataChangeKind.RANGE_UPDATE
        }
        return MergeOutcome(persistentKLineBars(result, performanceTracker), KLineDataChange(
            kind,
            prefix until (first.size - trailingUnchanged),
            prefix until (result.size - trailingUnchanged),
            prefix,
            trailingUnchanged,
        ), changed = result.size != first.size || prefix != first.size, insertedBefore = insertedBefore)
    }

    private fun updateData(
        bars: List<KLineBar>,
        hasMoreBefore: Boolean = snapshot.hasMoreBefore,
        hasMoreAfter: Boolean = snapshot.hasMoreAfter,
        change: KLineDataChange,
        knownChanged: Boolean? = null,
    ) {
        val changed = knownChanged ?: !barsEqual(snapshot.bars, bars)
        if (!changed) {
            publish(snapshot.copy(hasMoreBefore = hasMoreBefore, hasMoreAfter = hasMoreAfter))
            return
        }
        performanceTracker?.recordStoreChangeStep()
        val oldBars = snapshot.bars
        publishData(snapshot.copy(
            bars = bars,
            hasMoreBefore = hasMoreBefore,
            hasMoreAfter = hasMoreAfter,
            dataRevision = snapshot.dataRevision + 1,
        ), oldBars, change)
    }

    private fun publishData(
        next: KLineStoreSnapshot,
        oldBars: List<KLineBar> = snapshot.bars,
        change: KLineDataChange = fullChange(snapshot.bars.size, next.bars.size),
    ) {
        val crosshair = next.crosshair
        val matchedIndex = crosshair?.let { value -> next.bars.exactTimestampIndex(value.timestamp) }
        val reconciledCrosshair = if (matchedIndex != null && matchedIndex >= 0) crosshair.copy(index = matchedIndex) else null
        val lostActiveCrosshair = crosshair != null && reconciledCrosshair == null && next.interactionState == KLineInteractionState.CROSSHAIR
        val reconciledSession = if (next.interactionState == KLineInteractionState.CROSSHAIR && reconciledCrosshair != null) {
            KLineInteractionSession.Crosshair(reconciledCrosshair)
        } else {
            next.interactionSession
        }
        val indicatorBatch = calculateIndicatorBatch(next.indicatorInstances, next.bars, next.dataRevision, oldBars, change)
        publish(next.copy(
            crosshair = reconciledCrosshair,
            interactionState = if (lostActiveCrosshair) KLineInteractionState.IDLE else next.interactionState,
            interactionSession = if (lostActiveCrosshair) null else reconciledSession,
            interactionRevision = if (crosshair != reconciledCrosshair) next.interactionRevision + 1 else next.interactionRevision,
            indicatorResults = indicatorBatch.results,
            indicatorRevision = snapshot.indicatorRevision + if (indicatorBatch.changed) 1 else 0,
        ), force = true)
    }

    private fun calculateIndicators(
        instances: List<KLineIndicatorInstance>,
        bars: List<KLineBar>,
        dataRevision: Long,
        oldBars: List<KLineBar>? = null,
        change: KLineDataChange? = null,
    ): Map<String, KLineIndicatorResult> = calculateIndicatorBatch(instances, bars, dataRevision, oldBars, change).results

    private fun calculateIndicatorBatch(
        instances: List<KLineIndicatorInstance>,
        bars: List<KLineBar>,
        dataRevision: Long,
        oldBars: List<KLineBar>? = null,
        change: KLineDataChange? = null,
    ): IndicatorBatch {
        val activeInstances = instances.filter(KLineIndicatorInstance::visible)
        indicatorEngine.retainActive(activeInstances, dataRevision)
        val results = mutableMapOf<String, KLineIndicatorResult>()
        var changed = false
        activeInstances.forEach { instance ->
            val context = change?.let { dataChange -> snapshot.indicatorResults[instance.id]?.let { previous ->
                val tracker = performanceTracker
                val key = KLinePerformanceKey(instance.id, instance.templateName)
                KLineIndicatorUpdateContext(
                    if (tracker == null) oldBars.orEmpty() else TrackedKLineBars(oldBars.orEmpty(), tracker, key, old = true),
                    if (tracker == null) bars else TrackedKLineBars(bars, tracker, key, old = false),
                    previous,
                    dataChange,
                    tracker,
                    key,
                )
            } }
            when (val outcome = indicatorEngine.calculateIsolated(instance, bars, dataRevision, context)) {
                is KLineIndicatorCalculation.Success -> {
                    results[instance.id] = outcome.result
                    changed = changed || outcome.changed
                }
                is KLineIndicatorCalculation.Failure -> {
                    changed = changed || snapshot.indicatorResults.containsKey(instance.id)
                    if (outcome.shouldReport) {
                        reportError(KLineError(
                            code = KLineErrorCode.INDICATOR_CALCULATION_FAILED,
                            message = outcome.cause.message ?: "Indicator calculation failed",
                            instanceId = instance.id,
                            templateName = instance.templateName,
                        ))
                    }
                }
            }
        }
        changed = changed || snapshot.indicatorResults.keys != results.keys
        return IndicatorBatch(results.toMap(), changed)
    }

    private fun reportError(error: KLineError) {
        try {
            onError(error)
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
        }
        errorObservers.toList().forEach { observer ->
            try {
                observer(error)
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
            }
        }
    }

    private fun publish(next: KLineStoreSnapshot, force: Boolean = false) {
        val previous = snapshot
        if (!force) {
            performanceTracker?.recordPublishComparisonStep()
            if (sameSnapshotState(previous, next)) return
        }
        snapshot = next
        observers.toList().forEach { observer ->
            try {
                observer(previous, next)
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
            }
        }
    }

    private fun barsEqual(first: List<KLineBar>, second: List<KLineBar>): Boolean {
        if (first === second) return true
        if (first.size != second.size) return false
        val left = first.iterator()
        val right = second.iterator()
        while (left.hasNext()) {
            performanceTracker?.recordBarOldItemRead()
            if (left.next() != right.next()) return false
        }
        return true
    }

    private fun sameSnapshotState(first: KLineStoreSnapshot, second: KLineStoreSnapshot): Boolean =
        first === second ||
            first.symbol == second.symbol &&
            first.period == second.period &&
            first.bars === second.bars &&
            first.hasMoreBefore == second.hasMoreBefore &&
            first.hasMoreAfter == second.hasMoreAfter &&
            first.loadState == second.loadState &&
            first.viewport == second.viewport &&
            first.panes === second.panes &&
            first.indicatorInstances === second.indicatorInstances &&
            first.indicatorResults === second.indicatorResults &&
            first.overlayInstances === second.overlayInstances &&
            first.selectedOverlayId == second.selectedOverlayId &&
            first.interactionState == second.interactionState &&
            first.interactionSession == second.interactionSession &&
            first.crosshair == second.crosshair &&
            first.clickSelection == second.clickSelection &&
            first.dataRevision == second.dataRevision &&
            first.viewportRevision == second.viewportRevision &&
            first.paneRevision == second.paneRevision &&
            first.indicatorRevision == second.indicatorRevision &&
            first.overlayRevision == second.overlayRevision &&
            first.interactionRevision == second.interactionRevision &&
            first.theme == second.theme &&
            first.styleRevision == second.styleRevision &&
            first.formatters == second.formatters

    private fun normalizeOverlayOrder(instances: List<KLineOverlayInstance>): List<KLineOverlayInstance> =
        instances.sortedBy(KLineOverlayInstance::zIndex)
}

private fun KLineOverlayInstance.immutableCopy(): KLineOverlayInstance = copy(
    points = points.toList(),
    styles = styles.mapValues { (_, style) -> style.copy(lineDash = style.lineDash.toList()) },
    extendData = extendData.toMap(),
)

private fun KLineInteractionSession.immutableCopy(): KLineInteractionSession = when (this) {
    is KLineInteractionSession.Crosshair -> copy()
    is KLineInteractionSession.Panning -> copy()
    is KLineInteractionSession.Scaling -> copy()
    is KLineInteractionSession.DrawingOverlay -> copy(points = points.toList())
    is KLineInteractionSession.DraggingOverlayPoint -> copy(originalPoints = originalPoints.toList())
    is KLineInteractionSession.DraggingOverlay -> copy(originalPoints = originalPoints.toList())
    is KLineInteractionSession.ResizingPane -> copy(
        initialPanes = initialPanes.toList(),
        initialHeights = initialHeights.toList(),
    )
}

private data class IndicatorBatch(
    val results: Map<String, KLineIndicatorResult>,
    val changed: Boolean,
)

private data class MergeOutcome(
    val bars: List<KLineBar>,
    val change: KLineDataChange,
    val changed: Boolean,
    val insertedBefore: Int,
)

private fun fullChange(oldSize: Int, newSize: Int) = KLineDataChange(
    KLineDataChangeKind.FULL_REPLACE,
    0 until oldSize,
    0 until newSize,
    unchangedPrefixCount = 0,
    unchangedSuffixCount = 0,
)

internal fun interface KLineStoreSubscription {
    fun cancel()
}

fun interface KLineErrorSubscription {
    fun cancel()
}
