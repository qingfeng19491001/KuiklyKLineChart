package com.tencent.kuiklybase.kline.indicator

import com.tencent.kuiklybase.kline.data.KLineBar
import com.tencent.kuiklybase.kline.overlay.KLineBuiltInOverlays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KLineIndicatorEngineTest {
    @Test
    fun chartScopedRegistryCalculatesMovingAverageWithoutLeakingTemplates() {
        val firstRegistry = KLineExtensionRegistry()
        val secondRegistry = KLineExtensionRegistry()
        firstRegistry.register(KLineMovingAverageIndicator)
        val engine = KLineIndicatorEngine(firstRegistry)
        val instance = KLineIndicatorInstance(
            id = "price-ma",
            templateName = "MA",
            paneId = "price",
            params = listOf(3.0),
            precision = 2,
        )

        val result = engine.calculate(
            instance = instance,
            bars = (1L..4L).map { bar(it, it.toDouble()) },
            dataRevision = 1,
        )

        assertEquals(listOf("MA3"), result.figures.map(KLineIndicatorFigureResult::key))
        assertEquals(listOf(null, null, 2.0, 3.0), result.figures.single().values)
        assertEquals(KLineIndicatorSeries.PRICE, result.series)
        assertNull(secondRegistry.find("MA"))
    }

    @Test
    fun bollUsesPopulationDeviationOverTheFullWindow() {
        val result = KLineBollIndicator.calculate(
            bars = (1L..20L).map { bar(it, it.toDouble()) },
            params = listOf(20.0, 2.0),
        )

        assertEquals(listOf("BOLL", "UP", "DN"), result.figures.map(KLineIndicatorFigureResult::key))
        assertClose(10.5, result.figures[0].values.last())
        assertClose(22.032562594670797, result.figures[1].values.last())
        assertClose(-1.0325625946707966, result.figures[2].values.last())
        assertTrue(result.figures.all { figure -> figure.values.take(19).all { it == null } })
    }

    @Test
    fun expmaStartsAtTheFirstCloseForEachConfiguredPeriod() {
        val result = KLineExpmaIndicator.calculate(
            bars = listOf(bar(1, 10.0), bar(2, 12.0)),
            params = listOf(12.0, 50.0),
        )

        assertEquals(listOf("EXPMA12", "EXPMA50"), result.figures.map(KLineIndicatorFigureResult::key))
        assertClose(10.307692307692307, result.figures[0].values.last())
        assertClose(10.07843137254902, result.figures[1].values.last())
    }

    @Test
    fun builtInTemplatesExposeCompleteIndicatorFamiliesWithKnownResults() {
        assertEquals(
            listOf("MA", "BOLL", "EXPMA", "BBI", "ENE", "VOL", "AMOUNT", "MACD", "KDJ", "RSI", "WR", "BBD"),
            KLineBuiltInIndicators.templates.map(KLineIndicatorTemplate::name),
        )

        val rising = (1L..24L).map { index -> bar(index, index.toDouble()) }
        assertClose(18.875, KLineBbiIndicator.calculate(rising, emptyList()).figures.single().values.last())
        val ene = KLineEneIndicator.calculate(rising.take(10), listOf(10.0, 11.0, 9.0))
        assertClose(6.105, ene.figures[0].values.last())
        assertClose(5.5, ene.figures[1].values.last())
        assertClose(5.005, ene.figures[2].values.last())

        val volumeBars = listOf(
            bar(1, 10.0, volume = 10.0),
            bar(2, 11.0, volume = 20.0),
            bar(3, 12.0, volume = 30.0, turnover = 999.0),
        )
        val volume = KLineVolumeIndicator.calculate(volumeBars, listOf(3.0))
        assertEquals(listOf(KLineIndicatorFigureType.BAR, KLineIndicatorFigureType.LINE), volume.figures.map { it.type })
        assertEquals(30.0, volume.figures[0].values.last())
        assertEquals(20.0, volume.figures[1].values.last())
        assertEquals(999.0, KLineAmountIndicator.calculate(volumeBars, emptyList()).figures.single().values.last())

        val macd = KLineMacdIndicator.calculate(listOf(bar(1, 1.0), bar(2, 2.0)), listOf(12.0, 26.0, 9.0))
        assertClose(0.07977207977207978, macd.figures[0].values.last())
        assertClose(0.01595441595441596, macd.figures[1].values.last())
        assertClose(0.12763532763532764, macd.figures[2].values.last())

        val directional = listOf(
            bar(1, close = 5.0, high = 10.0, low = 0.0),
            bar(2, close = 10.0, high = 10.0, low = 0.0),
            bar(3, close = 0.0, high = 10.0, low = 0.0),
        )
        val kdj = KLineKdjIndicator.calculate(directional, listOf(3.0, 3.0, 3.0))
        assertClose(33.333333333333336, kdj.figures[0].values.last())
        assertClose(44.44444444444445, kdj.figures[1].values.last())
        assertClose(11.1111111111111, kdj.figures[2].values.last())
        assertEquals(100.0, KLineWrIndicator.calculate(directional, listOf(3.0)).figures.single().values.last())

        val rsi = KLineRsiIndicator.calculate(
            listOf(bar(1, 1.0), bar(2, 3.0), bar(3, 2.0)),
            listOf(2.0),
        )
        assertClose(66.66666666666666, rsi.figures.single().values.last())

        val bbdBars = (1L..5L).map { index -> bar(index, index.toDouble(), volume = 100.0) }
        val bbd = KLineBbdIndicator.calculate(bbdBars, listOf(5.0, 5.0))
        assertClose(66.66666666666667, bbd.figures[0].values.last())
        assertClose(66.66666666666667, bbd.figures[1].values.last())
    }

    @Test
    fun engineCacheIsSharedByEquivalentInstancesAndInvalidatedByDataOrRegistryRevision() {
        val template = CountingTemplate()
        val registry = KLineExtensionRegistry(listOf(template))
        val engine = KLineIndicatorEngine(registry)
        val first = KLineIndicatorInstance("first", "COUNT", "price", emptyList(), 2)
        val second = first.copy(id = "second")
        val bars = listOf(bar(1, 10.0))

        engine.calculate(first, bars, dataRevision = 1)
        engine.calculate(second, bars, dataRevision = 1)
        assertEquals(1, template.calculationCount)

        registry.registerOverlay(KLineBuiltInOverlays.HORIZONTAL_LINE)
        engine.calculate(first, bars, dataRevision = 1)
        assertEquals(1, template.calculationCount)

        engine.calculate(first, bars, dataRevision = 2)
        assertEquals(2, template.calculationCount)
        registry.register(template)
        engine.calculate(first, bars, dataRevision = 2)
        assertEquals(3, template.calculationCount)
    }

    private fun bar(
        timestamp: Long,
        close: Double,
        high: Double = close,
        low: Double = close,
        volume: Double? = null,
        turnover: Double? = null,
    ) = KLineBar(
        timestamp = timestamp,
        open = close,
        high = high,
        low = low,
        close = close,
        volume = volume,
        turnover = turnover,
    )

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double = 1e-9) {
        assertTrue(actual != null && kotlin.math.abs(expected - actual) <= tolerance)
    }

    private class CountingTemplate : KLineIndicatorTemplate {
        var calculationCount = 0
        override val name = "COUNT"
        override val defaultParams = emptyList<Double>()
        override val series = KLineIndicatorSeries.PRICE
        override val figures = listOf(KLineIndicatorFigure("COUNT", "COUNT", KLineIndicatorFigureType.LINE))

        override fun calculate(bars: List<KLineBar>, params: List<Double>): KLineIndicatorResult {
            calculationCount++
            return KLineIndicatorResult(
                name,
                series,
                listOf(KLineIndicatorFigureResult("COUNT", KLineIndicatorFigureType.LINE, bars.map(KLineBar::close))),
            )
        }
    }
}
