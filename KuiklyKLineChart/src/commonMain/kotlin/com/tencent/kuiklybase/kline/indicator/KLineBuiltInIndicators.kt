package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import kotlin.jvm.JvmStatic

object KLineBuiltInIndicators {
    val templates: List<KLineIndicatorTemplate> = listOf(
        KLineMovingAverageIndicator,
        KLineBollIndicator,
        KLineExpmaIndicator,
        KLineBbiIndicator,
        KLineEneIndicator,
        KLineVolumeIndicator,
        KLineAmountIndicator,
        KLineMacdIndicator,
        KLineKdjIndicator,
        KLineRsiIndicator,
        KLineWrIndicator,
        KLineBbdIndicator,
    )

    @JvmStatic
    val MA: KLineIndicatorTemplate get() = requireByName("MA")
    @JvmStatic
    val BOLL: KLineIndicatorTemplate get() = requireByName("BOLL")
    @JvmStatic
    val EXPMA: KLineIndicatorTemplate get() = requireByName("EXPMA")
    @JvmStatic
    val BBI: KLineIndicatorTemplate get() = requireByName("BBI")
    @JvmStatic
    val ENE: KLineIndicatorTemplate get() = requireByName("ENE")
    @JvmStatic
    val VOLUME: KLineIndicatorTemplate get() = requireByName("VOL")
    @JvmStatic
    val AMOUNT: KLineIndicatorTemplate get() = requireByName("AMOUNT")
    @JvmStatic
    val MACD: KLineIndicatorTemplate get() = requireByName("MACD")
    @JvmStatic
    val KDJ: KLineIndicatorTemplate get() = requireByName("KDJ")
    @JvmStatic
    val RSI: KLineIndicatorTemplate get() = requireByName("RSI")
    @JvmStatic
    val WR: KLineIndicatorTemplate get() = requireByName("WR")
    @JvmStatic
    val BBD: KLineIndicatorTemplate get() = requireByName("BBD")

    private fun requireByName(name: String): KLineIndicatorTemplate =
        templates.firstOrNull { it.name == name } ?: error("Built-in indicator not found: $name")

    fun registry(): KLineExtensionRegistry = KLineExtensionRegistry(
        templates = templates,
        overlayTemplates = KLineBuiltInOverlays.templates,
    )
}
