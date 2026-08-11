package com.tencent.kuiklybase.kline.format

import kotlin.test.Test
import kotlin.test.assertEquals

class KLineFormatterTest {
    @Test
    fun defaultFormatterHandlesAllValueKindsAndFallbacks() {
        val formatter: KLineFormatter = DefaultKLineFormatter(
            KLineFormatOptions(pricePrecision = 2, volumePrecision = 1, useGrouping = true),
        )

        assertEquals("1,234.57", formatter.formatPrice(1234.565))
        assertEquals("-1,234.50", formatter.formatPrice(-1234.5))
        assertEquals("1.5K", formatter.formatVolume(1_500.0))
        assertEquals("1.3B", formatter.formatTurnover(1_250_000_000.0))
        assertEquals("+12.35%", formatter.formatPercentage(0.12345))
        assertEquals("-0.01%", formatter.formatPercentage(-0.00005))
        assertEquals("12.346", formatter.formatIndicatorValue(12.3455, 3))
        assertEquals("--", formatter.formatPrice(null))
        assertEquals("--", formatter.formatVolume(Double.POSITIVE_INFINITY))
        assertEquals("--", formatter.formatDate(null))
    }

    @Test
    fun dateFormattingObservesUtcAndShanghaiAcrossADayBoundary() {
        val utc = DefaultKLineFormatter(KLineFormatOptions(timeZone = KLineTimeZone.UTC))
        val shanghai = DefaultKLineFormatter(KLineFormatOptions(timeZone = KLineTimeZone.ASIA_SHANGHAI))

        assertEquals("2024-01-01 20:30", utc.formatDate(1_704_141_000_000L))
        assertEquals("2024-01-02 04:30", shanghai.formatDate(1_704_141_000_000L))
    }

    @Test
    fun languageOptionChangesCompactUnitsWithRealOutput() {
        val english = DefaultKLineFormatter(KLineFormatOptions(language = KLineLanguage.ENGLISH))
        val chinese = DefaultKLineFormatter(KLineFormatOptions(language = KLineLanguage.CHINESE))

        assertEquals("12.00K", english.formatVolume(12_000.0))
        assertEquals("1.20万", chinese.formatVolume(12_000.0))
        assertEquals("1.20亿", chinese.formatTurnover(120_000_000.0))
    }

    @Test
    fun separatorSignSymbolPrecisionAndFallbackOptionsAreConnected() {
        val formatter = DefaultKLineFormatter(KLineFormatOptions(
            pricePrecision = 0,
            turnoverPrecision = 3,
            percentagePrecision = 1,
            useGrouping = false,
            showPositiveSign = false,
            showPercentageSymbol = false,
            fallbackText = "N/A",
        ))

        assertEquals("1235", formatter.formatPrice(1234.5))
        assertEquals("1.235M", formatter.formatTurnover(1_234_500.0))
        assertEquals("12.3", formatter.formatPercentage(0.1234))
        assertEquals("N/A", formatter.formatIndicatorValue(null, 2))
    }

    @Test
    fun finiteValuesLargerThanLongRangeDoNotSaturateDuringFormatting() {
        val formatter = DefaultKLineFormatter()

        assertEquals("100,000,000,000,000,000,000.00", formatter.formatPrice(1.0e20))
        assertEquals("--", formatter.formatPercentage(1.0e308))
    }

    @Test
    fun longScalingBoundaryAndAdjacentDoublesNeverSaturate() {
        val formatter = DefaultKLineFormatter(KLineFormatOptions(pricePrecision = 2))
        val boundary = Long.MAX_VALUE.toDouble() / 100.0
        val lower = Double.fromBits(boundary.toBits() - 1)
        val upper = Double.fromBits(boundary.toBits() + 1)

        assertEquals("92,233,720,368,547,744.00", formatter.formatPrice(lower))
        assertEquals("92,233,720,368,547,760.00", formatter.formatPrice(boundary))
        assertEquals("92,233,720,368,547,776.00", formatter.formatPrice(upper))
        assertEquals("-92,233,720,368,547,760.00", formatter.formatPrice(-boundary))
    }

    @Test
    fun mutableFormatterOptionsResolveToComposableImmutableBehavior() {
        val options = KLineFormatterOptions().apply {
            date = KLineFormatOptions(timeZone = KLineTimeZone.UTC)
            price = KLineFormatOptions(pricePrecision = 0, useGrouping = false)
            volume = KLineFormatOptions(volumePrecision = 2, language = KLineLanguage.CHINESE)
            turnover = KLineFormatOptions(turnoverPrecision = 1, language = KLineLanguage.ENGLISH)
            percentage = KLineFormatOptions(percentagePrecision = 1, showPositiveSign = false, showPercentageSymbol = false)
            indicatorValue = KLineFormatOptions(useGrouping = false, fallbackText = "N/A")
        }
        val resolved = options.resolved()
        options.price = KLineFormatOptions(pricePrecision = 4)

        assertEquals("1970-01-01 00:00", resolved.formatDate(1))
        assertEquals("2", resolved.formatPrice(2.25))
        assertEquals("1.20万", resolved.formatVolume(12_000.0))
        assertEquals("1.2K", resolved.formatTurnover(1_200.0))
        assertEquals("5.0", resolved.formatPercentage(0.05))
        assertEquals("N/A", resolved.formatIndicatorValue(null, 3))
        assertEquals("2", resolved.formatPrice(2.25))
    }

    @Test
    fun publicBoundaryTableIsDeterministicAndBounded() {
        val formatter = DefaultKLineFormatter()
        val twoPow53 = 9_007_199_254_740_992.0

        assertEquals("--", formatter.formatPrice(Double.MAX_VALUE))
        assertEquals("0.00", formatter.formatPrice(Double.MIN_VALUE))
        assertEquals("9,007,199,254,740,991.00", formatter.formatPrice(Double.fromBits(twoPow53.toBits() - 1)))
        assertEquals("9,007,199,254,740,992.00", formatter.formatPrice(twoPow53))
        assertEquals("9,007,199,254,740,994.00", formatter.formatPrice(Double.fromBits(twoPow53.toBits() + 1)))
        assertEquals("0.00", formatter.formatPrice(0.0))
        assertEquals("0.00", formatter.formatPrice(-0.0))
        assertEquals("1,000.00", formatter.formatPrice(999.995))
        assertEquals("3", DefaultKLineFormatter(KLineFormatOptions(pricePrecision = 0)).formatPrice(2.5))
        assertEquals("1.000000000001", formatter.formatIndicatorValue(1.0000000000005, 12))
        assertEquals("--", formatter.formatDate(Long.MIN_VALUE))
        assertEquals("--", formatter.formatDate(Long.MAX_VALUE))
    }

    @Test
    fun formatterSnapshotCopyBuildsBehaviorFromItsOwnOptions() {
        val original = KLineFormatterOptions().resolved()
        val copied = original.copy(price = original.price.copy(pricePrecision = 0, useGrouping = false))

        assertEquals("1.25", original.formatPrice(1.25))
        assertEquals("1", copied.formatPrice(1.25))
        assertEquals(original, KLineFormatterOptions().resolved())
    }
}
