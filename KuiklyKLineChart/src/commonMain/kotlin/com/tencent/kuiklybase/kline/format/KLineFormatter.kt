package com.tencent.kuiklybase.kline.format

import kotlin.math.abs
import kotlin.math.floor

enum class KLineTimeZone(val offsetMinutes: Int) { ASIA_SHANGHAI(480), UTC(0) }
enum class KLineLanguage { CHINESE, ENGLISH }

data class KLineFormatOptions(
    val pricePrecision: Int = 2,
    val volumePrecision: Int = 2,
    val turnoverPrecision: Int = volumePrecision,
    val percentagePrecision: Int = 2,
    val useGrouping: Boolean = true,
    val showPositiveSign: Boolean = true,
    val showPercentageSymbol: Boolean = true,
    val fallbackText: String = "--",
    val timeZone: KLineTimeZone = KLineTimeZone.ASIA_SHANGHAI,
    val language: KLineLanguage = KLineLanguage.ENGLISH,
) {
    init {
        require(pricePrecision in 0..12 && volumePrecision in 0..12 && turnoverPrecision in 0..12 && percentagePrecision in 0..12) { "Precision must be between 0 and 12" }
        require(fallbackText.isNotEmpty()) { "Fallback text must not be empty" }
    }
}

interface KLineFormatter {
    fun formatDate(timestampMillis: Long?): String
    fun formatPrice(value: Double?): String
    fun formatVolume(value: Double?): String
    fun formatTurnover(value: Double?): String
    fun formatPercentage(value: Double?): String
    fun formatIndicatorValue(value: Double?, precision: Int): String
}

class KLineFormatterOptions {
    var date: KLineFormatOptions = KLineFormatOptions()
    var price: KLineFormatOptions = KLineFormatOptions()
    var volume: KLineFormatOptions = KLineFormatOptions()
    var turnover: KLineFormatOptions = KLineFormatOptions()
    var percentage: KLineFormatOptions = KLineFormatOptions()
    var indicatorValue: KLineFormatOptions = KLineFormatOptions()

    fun resolved(): KLineFormatters = KLineFormatters(
        date = date.copy(),
        price = price.copy(),
        volume = volume.copy(),
        turnover = turnover.copy(),
        percentage = percentage.copy(),
        indicatorValue = indicatorValue.copy(),
    )
}

data class KLineFormatters(
    val date: KLineFormatOptions,
    val price: KLineFormatOptions,
    val volume: KLineFormatOptions,
    val turnover: KLineFormatOptions,
    val percentage: KLineFormatOptions,
    val indicatorValue: KLineFormatOptions,
) : KLineFormatter {
    private val dateDelegate = DefaultKLineFormatter(date)
    private val priceDelegate = DefaultKLineFormatter(price)
    private val volumeDelegate = DefaultKLineFormatter(volume)
    private val turnoverDelegate = DefaultKLineFormatter(turnover)
    private val percentageDelegate = DefaultKLineFormatter(percentage)
    private val indicatorValueDelegate = DefaultKLineFormatter(indicatorValue)

    override fun formatDate(timestampMillis: Long?): String =
        dateDelegate.formatDate(timestampMillis)
    override fun formatPrice(value: Double?): String = priceDelegate.formatPrice(value)
    override fun formatVolume(value: Double?): String = volumeDelegate.formatVolume(value)
    override fun formatTurnover(value: Double?): String = turnoverDelegate.formatTurnover(value)
    override fun formatPercentage(value: Double?): String = percentageDelegate.formatPercentage(value)
    override fun formatIndicatorValue(value: Double?, precision: Int): String =
        indicatorValueDelegate.formatIndicatorValue(value, precision)

    companion object {
        val DEFAULT: KLineFormatters = KLineFormatterOptions().resolved()
    }
}

class DefaultKLineFormatter(val options: KLineFormatOptions = KLineFormatOptions()) : KLineFormatter {
    override fun formatDate(timestampMillis: Long?): String {
        if (timestampMillis == null) return options.fallbackText
        val localSeconds = floorDiv(timestampMillis, 1000L) + options.timeZone.offsetMinutes * 60L
        val days = floorDiv(localSeconds, 86_400L)
        val secondsOfDay = floorMod(localSeconds, 86_400L).toInt()
        val date = civilFromDays(days)
        if (date.first !in 0..9999) return options.fallbackText
        return "${date.first.pad(4)}-${date.second.pad(2)}-${date.third.pad(2)} " +
            "${(secondsOfDay / 3600).pad(2)}:${((secondsOfDay % 3600) / 60).pad(2)}"
    }

    override fun formatPrice(value: Double?) = fixedOrFallback(value, options.pricePrecision, options.useGrouping)
    override fun formatVolume(value: Double?) = compact(value, options.volumePrecision)
    override fun formatTurnover(value: Double?) = compact(value, options.turnoverPrecision)
    override fun formatPercentage(value: Double?): String {
        if (value == null || !value.isFinite()) return options.fallbackText
        val percent = value * 100.0
        if (!percent.isFinite() || abs(percent) > MAX_FIXED_MAGNITUDE) return options.fallbackText
        val sign = if (options.showPositiveSign && percent > 0.0) "+" else ""
        return sign + fixed(percent, options.percentagePrecision, false) + if (options.showPercentageSymbol) "%" else ""
    }
    override fun formatIndicatorValue(value: Double?, precision: Int): String {
        require(precision in 0..12) { "Precision must be between 0 and 12" }
        return fixedOrFallback(value, precision, options.useGrouping)
    }

    private fun compact(value: Double?, precision: Int): String {
        if (value == null || !value.isFinite()) return options.fallbackText
        val magnitude = abs(value)
        val (divisor, suffix) = when (options.language) {
            KLineLanguage.ENGLISH -> when {
                magnitude >= 1_000_000_000.0 -> 1_000_000_000.0 to "B"
                magnitude >= 1_000_000.0 -> 1_000_000.0 to "M"
                magnitude >= 1_000.0 -> 1_000.0 to "K"
                else -> 1.0 to ""
            }
            KLineLanguage.CHINESE -> when {
                magnitude >= 100_000_000.0 -> 100_000_000.0 to "亿"
                magnitude >= 10_000.0 -> 10_000.0 to "万"
                else -> 1.0 to ""
            }
        }
        val scaled = value / divisor
        if (abs(scaled) > MAX_FIXED_MAGNITUDE) return options.fallbackText
        return fixed(scaled, precision, divisor == 1.0 && options.useGrouping) + suffix
    }

    private fun fixedOrFallback(value: Double?, precision: Int, grouping: Boolean): String =
        if (value == null || !value.isFinite() || abs(value) > MAX_FIXED_MAGNITUDE) {
            options.fallbackText
        } else {
            fixed(value, precision, grouping)
        }
}

private const val MAX_FIXED_MAGNITUDE: Double = 1.0e100

private fun fixed(value: Double, precision: Int, grouping: Boolean): String {
    val absolute = abs(value)
    val sign = if (value < 0.0) "-" else ""
    val decimal = decimalDigits(absolute)
    val scaledLength = decimal.decimalIndex + precision
    val paddedDigits = decimal.digits.padEnd(scaledLength + 1, '0')
    var scaled = paddedDigits.substring(0, scaledLength).ifEmpty { "0" }
    if (paddedDigits[scaledLength] >= '5') scaled = incrementDecimalDigits(scaled)
    scaled = scaled.trimStart('0').ifEmpty { "0" }
    val integer: String
    val fraction: String
    if (precision == 0) {
        integer = scaled
        fraction = ""
    } else {
        val fixedDigits = scaled.padStart(precision + 1, '0')
        integer = fixedDigits.dropLast(precision)
        fraction = fixedDigits.takeLast(precision)
    }
    val grouped = if (grouping) groupDigits(integer) else integer
    return if (precision == 0) sign + grouped else "$sign$grouped.$fraction"
}

private fun groupDigits(value: String): String = value.reversed().chunked(3).joinToString(",").reversed()

private data class DecimalDigits(val digits: String, val decimalIndex: Int)

private fun decimalDigits(value: Double): DecimalDigits {
    if (value >= 9_007_199_254_740_992.0) return exactIntegralDecimalDigits(value)
    val raw = value.toString().uppercase()
    val mantissa = raw.substringBefore('E')
    val exponent = raw.substringAfter('E', "0").toInt()
    val decimalIndex = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
    var digits = mantissa.replace(".", "")
    var adjustedIndex = decimalIndex + exponent
    if (adjustedIndex <= 0) {
        digits = "0".repeat(1 - adjustedIndex) + digits
        adjustedIndex = 1
    }
    return DecimalDigits(digits, adjustedIndex)
}

private fun exactIntegralDecimalDigits(value: Double): DecimalDigits {
    val bits = value.toBits()
    val exponentBits = ((bits ushr 52) and 0x7FFL).toInt()
    val fraction = bits and ((1L shl 52) - 1L)
    val significand = if (exponentBits == 0) fraction else fraction or (1L shl 52)
    val binaryPower = if (exponentBits == 0) -1074 else exponentBits - 1023 - 52
    var digits = significand.toString()
    if (binaryPower >= 0) {
        repeat(binaryPower) { digits = multiplyDecimalDigits(digits, 2) }
        return DecimalDigits(digits, digits.length)
    }
    repeat(-binaryPower) { digits = multiplyDecimalDigits(digits, 5) }
    return DecimalDigits(digits, digits.length + binaryPower)
}

private fun multiplyDecimalDigits(value: String, multiplier: Int): String {
    val reversed = StringBuilder(value.length + 1)
    var carry = 0
    for (index in value.lastIndex downTo 0) {
        val product = (value[index] - '0') * multiplier + carry
        reversed.append(('0'.code + product % 10).toChar())
        carry = product / 10
    }
    while (carry > 0) {
        reversed.append(('0'.code + carry % 10).toChar())
        carry /= 10
    }
    return reversed.reverse().toString()
}

private fun incrementDecimalDigits(value: String): String {
    val chars = value.toCharArray()
    for (index in chars.lastIndex downTo 0) {
        if (chars[index] < '9') {
            chars[index]++
            return chars.concatToString()
        }
        chars[index] = '0'
    }
    return "1" + chars.concatToString()
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    val r = a % b
    return if (r != 0L && (r < 0) != (b < 0)) q - 1 else q
}
private fun floorMod(a: Long, b: Long) = a - floorDiv(a, b) * b
private fun Int.pad(size: Int) = toString().padStart(size, '0')

private fun civilFromDays(epochDays: Long): Triple<Int, Int, Int> {
    var z = epochDays + 719468
    val era = floorDiv(z, 146097)
    val dayOfEra = z - era * 146097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    var year = (yearOfEra + era * 400).toInt()
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * monthPrime + 2) / 5 + 1).toInt()
    val month = (monthPrime + if (monthPrime < 10) 3 else -9).toInt()
    year += if (month <= 2) 1 else 0
    return Triple(year, month, day)
}
