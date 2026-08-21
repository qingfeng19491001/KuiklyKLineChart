package com.tencent.kuiklybase.kline.view

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLineDataSource

public data class KLineChartBinding(
    val dataSource: KLineDataSource,
    val controller: KLineChartController,
)

/**
 * 使用方均在 Kuikly 主线程，无需原子操作；
 * 避免依赖 kotlin.concurrent.atomics（OHOS Kotlin 2.0.21 工具链不提供该 API）。
 */
public object KLineChartBindingRegistry {
    private var nextId = 1L
    private var bindings: Map<String, KLineChartBinding> = emptyMap()

    public fun register(dataSource: KLineDataSource, controller: KLineChartController): String {
        val id = "kline-binding-${nextId++}"
        bindings = bindings + (id to KLineChartBinding(dataSource, controller))
        return id
    }

    public fun take(id: String): KLineChartBinding? {
        val binding = bindings[id] ?: return null
        bindings = bindings - id
        return binding
    }

    /** Releases an unconsumed binding when its owner abandons view creation. */
    public fun discard(id: String): Boolean {
        if (id !in bindings) return false
        bindings = bindings - id
        return true
    }
}
