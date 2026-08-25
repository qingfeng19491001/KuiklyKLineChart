package com.tencent.kuiklybase.kline.data

/** Binary-search helpers for lists whose timestamps are strictly increasing. */
internal fun List<KLineBar>.exactTimestampIndex(timestamp: Long): Int? {
    if (this is PersistentKLineBarList) return exactTimestampIndex(timestamp)
    val index = binarySearchBy(timestamp) { it.timestamp }
    return index.takeIf { it >= 0 }
}

internal fun List<KLineBar>.nearestTimestampIndex(timestamp: Long): Int? {
    if (this is PersistentKLineBarList) return nearestTimestampIndex(timestamp)
    if (isEmpty()) return null
    val exact = binarySearchBy(timestamp) { it.timestamp }
    if (exact >= 0) return exact
    val insertion = -exact - 1
    if (insertion == 0) return 0
    if (insertion == size) return lastIndex
    val beforeDistance = timestamp.toULong() - this[insertion - 1].timestamp.toULong()
    val afterDistance = this[insertion].timestamp.toULong() - timestamp.toULong()
    return if (beforeDistance <= afterDistance) insertion - 1 else insertion
}
