package com.kuikly.kuiklyklinechart.shared.demo

import com.tencent.kuiklybase.kline.data.KLineBar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random

object RandomBarGenerator {
    fun generate(
        count: Int,
        startTimestamp: Long = 1704067200L - count * 86_400L,
        stepSeconds: Long = 86_400L,
        basePrice: Double = 100.0,
        volatility: Double = 0.03,
        seed: Long = 42,
    ): List<KLineBar> {
        require(count > 0) { "Bar count must be positive" }
        val random = Random(seed)
        val bars = ArrayList<KLineBar>(count)
        var prevClose = basePrice
        repeat(count) { i ->
            val timestamp = startTimestamp + i * stepSeconds
            val drift = (random.nextDouble() - 0.5) * volatility
            val open = prevClose
            val closeBase = open * (1 + drift)
            val highExtra = random.nextDouble() * volatility * open * 0.5
            val lowExtra = random.nextDouble() * volatility * open * 0.5
            val rawHigh = max(open, closeBase) + highExtra
            val rawLow = min(open, closeBase) - lowExtra
            val close = roundToDecimals(closeBase, 2)
            val high = roundToDecimals(maxOf(rawHigh, open, close), 2)
            val low = roundToDecimals(minOf(rawLow, open, close), 2)
            val volume = roundToDecimals((1e5 + random.nextDouble() * 5e5), 0)
            val turnover = roundToDecimals(volume * (open + close) / 2, 0)
            bars += KLineBar(
                timestamp = timestamp,
                open = roundToDecimals(open, 2),
                high = high,
                low = low,
                close = close,
                volume = volume,
                turnover = turnover,
            )
            prevClose = close
        }
        return bars
    }

    fun defaultSymbolBars(count: Int = 500, seed: Long = 1337): List<KLineBar> =
        generate(count = count, basePrice = 55.5, volatility = 0.022, seed = seed)

    private fun roundToDecimals(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals.toDouble())
        return round(value * factor) / factor
    }
}
