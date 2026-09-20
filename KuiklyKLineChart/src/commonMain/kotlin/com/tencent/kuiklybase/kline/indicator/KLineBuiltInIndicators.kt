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
        KLineSmaIndicator,
        KLineEmaIndicator,
        KLineSarIndicator,
        KLineVolumeIndicator,
        KLineAmountIndicator,
        KLineObvIndicator,
        KLineMacdIndicator,
        KLineKdjIndicator,
        KLineRsiIndicator,
        KLineWrIndicator,
        KLineBbdIndicator,
        KLineCciIndicator,
        KLineDmiIndicator,
        KLineBiasIndicator,
        KLineRocIndicator,
        KLineBrarIndicator,
        KLineCrIndicator,
        KLineDmaIndicator,
        KLineEmvIndicator,
        KLineMtmIndicator,
        KLinePsyIndicator,
        KLineTrixIndicator,
        KLineVrIndicator,
        KLineAoIndicator,
        KLinePvtIndicator,
        KLineAvpIndicator,
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
    @JvmStatic
    val SMA: KLineIndicatorTemplate get() = requireByName("SMA")
    @JvmStatic
    val EMA: KLineIndicatorTemplate get() = requireByName("EMA")
    @JvmStatic
    val SAR: KLineIndicatorTemplate get() = requireByName("SAR")
    @JvmStatic
    val OBV: KLineIndicatorTemplate get() = requireByName("OBV")
    @JvmStatic
    val CCI: KLineIndicatorTemplate get() = requireByName("CCI")
    @JvmStatic
    val DMI: KLineIndicatorTemplate get() = requireByName("DMI")
    @JvmStatic
    val BIAS: KLineIndicatorTemplate get() = requireByName("BIAS")
    @JvmStatic
    val ROC: KLineIndicatorTemplate get() = requireByName("ROC")
    @JvmStatic
    val BRAR: KLineIndicatorTemplate get() = requireByName("BRAR")
    @JvmStatic
    val CR: KLineIndicatorTemplate get() = requireByName("CR")
    @JvmStatic
    val DMA: KLineIndicatorTemplate get() = requireByName("DMA")
    @JvmStatic
    val EMV: KLineIndicatorTemplate get() = requireByName("EMV")
    @JvmStatic
    val MTM: KLineIndicatorTemplate get() = requireByName("MTM")
    @JvmStatic
    val PSY: KLineIndicatorTemplate get() = requireByName("PSY")
    @JvmStatic
    val TRIX: KLineIndicatorTemplate get() = requireByName("TRIX")
    @JvmStatic
    val VR: KLineIndicatorTemplate get() = requireByName("VR")
    @JvmStatic
    val AO: KLineIndicatorTemplate get() = requireByName("AO")
    @JvmStatic
    val PVT: KLineIndicatorTemplate get() = requireByName("PVT")
    @JvmStatic
    val AVP: KLineIndicatorTemplate get() = requireByName("AVP")

    private fun requireByName(name: String): KLineIndicatorTemplate =
        templates.firstOrNull { it.name == name } ?: error("Built-in indicator not found: $name")

    fun registry(): KLineExtensionRegistry = KLineExtensionRegistry(
        templates = templates,
        overlayTemplates = KLineBuiltInOverlays.templates,
    )
}
