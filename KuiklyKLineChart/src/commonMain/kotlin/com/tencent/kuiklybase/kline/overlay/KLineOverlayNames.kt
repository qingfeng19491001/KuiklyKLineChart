package com.tencent.kuiklybase.kline.overlay

/**
 * Maps public DSL names (`HORIZONTAL_LINE`, `TEXT`) onto engine registry keys (`horizontal_line`).
 * Unknown names are left unchanged so custom [com.tencent.kuiklybase.kline.indicator.KLineExtensionRegistry]
 * keys keep their original spelling.
 */
internal fun canonicalizeOverlayTemplateName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return trimmed
    PUBLIC_ALIASES[trimmed.uppercase()]?.let { return it }
    val snake = trimmed.lowercase().replace('-', '_')
    return if (snake in BUILTIN_ENGINE_NAMES) snake else trimmed
}

private val BUILTIN_ENGINE_NAMES: Set<String> = setOf(
    KLineBuiltInOverlays.HORIZONTAL_LINE_NAME,
    KLineBuiltInOverlays.VERTICAL_LINE_NAME,
    KLineBuiltInOverlays.SEGMENT_NAME,
    KLineBuiltInOverlays.TREND_LINE_NAME,
    KLineBuiltInOverlays.RAY_NAME,
    KLineBuiltInOverlays.PRICE_LINE_NAME,
    KLineBuiltInOverlays.PARALLEL_LINES_NAME,
    KLineBuiltInOverlays.PRICE_CHANNEL_NAME,
    KLineBuiltInOverlays.FIBONACCI_RETRACEMENT_NAME,
    KLineBuiltInOverlays.TEXT_ANNOTATION_NAME,
    KLineBuiltInOverlays.FREEHAND_NAME,
)

private val PUBLIC_ALIASES: Map<String, String> = mapOf(
    "HORIZONTAL_LINE" to KLineBuiltInOverlays.HORIZONTAL_LINE_NAME,
    "VERTICAL_LINE" to KLineBuiltInOverlays.VERTICAL_LINE_NAME,
    "SEGMENT" to KLineBuiltInOverlays.SEGMENT_NAME,
    "TREND_LINE" to KLineBuiltInOverlays.TREND_LINE_NAME,
    "RAY" to KLineBuiltInOverlays.RAY_NAME,
    "PRICE_LINE" to KLineBuiltInOverlays.PRICE_LINE_NAME,
    "PARALLEL_LINES" to KLineBuiltInOverlays.PARALLEL_LINES_NAME,
    "PRICE_CHANNEL" to KLineBuiltInOverlays.PRICE_CHANNEL_NAME,
    "FIBONACCI_RETRACEMENT" to KLineBuiltInOverlays.FIBONACCI_RETRACEMENT_NAME,
    "TEXT" to KLineBuiltInOverlays.TEXT_ANNOTATION_NAME,
    "TEXT_ANNOTATION" to KLineBuiltInOverlays.TEXT_ANNOTATION_NAME,
    "FREEHAND" to KLineBuiltInOverlays.FREEHAND_NAME,
)
