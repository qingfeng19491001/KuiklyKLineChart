package com.tencent.kuiklybase.kline.indicator

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

    fun registry(): KLineExtensionRegistry = KLineExtensionRegistry(templates)
}
