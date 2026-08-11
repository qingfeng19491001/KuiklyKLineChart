package com.tencent.kuiklybase.kline.controller

import com.tencent.kuiklybase.kline.data.KLinePeriod
import com.tencent.kuiklybase.kline.data.KLineSymbol
import com.tencent.kuiklybase.kline.pane.KLinePane
import com.tencent.kuiklybase.kline.pane.KLinePaneState

class KLineChartController {
    private val pendingCommands = ArrayDeque<KLineControllerCommand>()
    private var target: KLineChartControllerTarget? = null

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
}

