package com.tencent.kuiklybase.kline.controller

import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.indicator.KLineIndicatorInstance
import com.tencent.kuiklybase.kline.overlay.KLineOverlayConfig
import com.tencent.kuiklybase.kline.overlay.KLineOverlayInstance
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneState
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class KLineChartController {
    private val pendingCommands = ArrayDeque<KLineControllerCommand>()
    private var target: KLineChartControllerTarget? = null
    private val overlayIdPrefix: Long = KLineOverlayControllerIdAllocator.allocatePrefix()
    private var nextOverlaySequence: Long = 1

    fun setMarket(
        symbol: KLineSymbol,
        period: KLinePeriod,
    ) = dispatch(KLineControllerCommand.SetMarket(symbol, period))

    fun scrollToLatest() = dispatch(KLineControllerCommand.ScrollToLatest)

    fun scrollToTimestamp(timestamp: Long) =
        dispatch(KLineControllerCommand.ScrollToTimestamp(timestamp))

    fun scrollByBars(count: Double) = dispatch(KLineControllerCommand.ScrollByBars(count))

    fun zoom(factor: Double) = dispatch(KLineControllerCommand.Zoom(factor))

    fun zoomAtTimestamp(
        factor: Double,
        timestamp: Long,
    ) = dispatch(KLineControllerCommand.ZoomAtTimestamp(factor, timestamp))

    fun resetViewport() = dispatch(KLineControllerCommand.ResetViewport)

    fun setPane(pane: KLinePane) = dispatch(KLineControllerCommand.SetPane(pane))

    fun removePane(paneId: String) = dispatch(KLineControllerCommand.RemovePane(paneId))

    fun movePane(
        paneId: String,
        index: Int,
    ) = dispatch(KLineControllerCommand.MovePane(paneId, index))

    fun setPaneState(
        paneId: String,
        state: KLinePaneState,
    ) = dispatch(KLineControllerCommand.SetPaneState(paneId, state))

    fun addIndicator(instance: KLineIndicatorInstance) =
        dispatch(KLineControllerCommand.AddIndicator(instance))

    fun updateIndicator(instance: KLineIndicatorInstance) =
        dispatch(KLineControllerCommand.UpdateIndicator(instance))

    fun removeIndicator(instanceId: String) =
        dispatch(KLineControllerCommand.RemoveIndicator(instanceId))

    fun createOverlay(config: KLineOverlayConfig): String {
        check(nextOverlaySequence > 0) { "Overlay id sequence exhausted" }
        val id = "overlay-$overlayIdPrefix-${nextOverlaySequence++}"
        dispatch(KLineControllerCommand.CreateOverlay(config.toInstance(id)))
        return id
    }

    fun updateOverlay(
        instanceId: String,
        config: KLineOverlayConfig,
    ) {
        require(instanceId.isNotBlank()) { "Overlay instance id must not be blank" }
        dispatch(KLineControllerCommand.UpdateOverlay(config.toInstance(instanceId)))
    }

    fun removeOverlay(instanceId: String) {
        require(instanceId.isNotBlank()) { "Overlay instance id must not be blank" }
        dispatch(KLineControllerCommand.RemoveOverlay(instanceId))
    }

    internal fun attach(target: KLineChartControllerTarget) {
        this.target = target
        while (pendingCommands.isNotEmpty()) {
            target.execute(pendingCommands.removeFirst())
        }
    }

    internal fun detach(target: KLineChartControllerTarget) {
        if (this.target === target) this.target = null
    }

    private fun dispatch(command: KLineControllerCommand) {
        val currentTarget = target
        if (currentTarget == null) {
            pendingCommands.addLast(command)
        } else {
            currentTarget.execute(command)
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private object KLineOverlayControllerIdAllocator {
    private val nextPrefix = AtomicLong(1)

    fun allocatePrefix(): Long {
        while (true) {
            val prefix = nextPrefix.load()
            check(prefix > 0) { "Overlay controller id prefix sequence exhausted" }
            val next = if (prefix == Long.MAX_VALUE) 0 else prefix + 1
            if (nextPrefix.compareAndSet(prefix, next)) return prefix
        }
    }
}

internal fun interface KLineChartControllerTarget {
    fun execute(command: KLineControllerCommand)
}

internal sealed interface KLineControllerCommand {
    data class SetMarket(val symbol: KLineSymbol, val period: KLinePeriod) : KLineControllerCommand
    data object ScrollToLatest : KLineControllerCommand
    data class ScrollToTimestamp(val timestamp: Long) : KLineControllerCommand
    data class ScrollByBars(val count: Double) : KLineControllerCommand
    data class Zoom(val factor: Double) : KLineControllerCommand
    data class ZoomAtTimestamp(val factor: Double, val timestamp: Long) : KLineControllerCommand
    data object ResetViewport : KLineControllerCommand
    data class SetPane(val pane: KLinePane) : KLineControllerCommand
    data class RemovePane(val paneId: String) : KLineControllerCommand
    data class MovePane(val paneId: String, val index: Int) : KLineControllerCommand
    data class SetPaneState(val paneId: String, val state: KLinePaneState) : KLineControllerCommand
    data class AddIndicator(val instance: KLineIndicatorInstance) : KLineControllerCommand
    data class UpdateIndicator(val instance: KLineIndicatorInstance) : KLineControllerCommand
    data class RemoveIndicator(val instanceId: String) : KLineControllerCommand
    data class CreateOverlay(val instance: KLineOverlayInstance) : KLineControllerCommand
    data class UpdateOverlay(val instance: KLineOverlayInstance) : KLineControllerCommand
    data class RemoveOverlay(val instanceId: String) : KLineControllerCommand
}
