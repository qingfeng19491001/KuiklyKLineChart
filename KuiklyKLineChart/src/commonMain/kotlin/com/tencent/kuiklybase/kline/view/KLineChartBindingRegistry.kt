package com.tencent.kuiklybase.kline.view

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.KLineDataSource
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public data class KLineChartBinding(
    val dataSource: KLineDataSource,
    val controller: KLineChartController,
)

@OptIn(ExperimentalAtomicApi::class)
public object KLineChartBindingRegistry {
    private val nextId = AtomicLong(1)
    private val bindings = AtomicReference<Map<String, KLineChartBinding>>(emptyMap())

    public fun register(dataSource: KLineDataSource, controller: KLineChartController): String {
        val id = "kline-binding-${nextId.fetchAndAdd(1)}"
        updateBindings { it + (id to KLineChartBinding(dataSource, controller)) }
        return id
    }

    public fun take(id: String): KLineChartBinding? {
        while (true) {
            val current = bindings.load()
            val binding = current[id] ?: return null
            if (bindings.compareAndSet(current, current - id)) return binding
        }
    }

    /** Releases an unconsumed binding when its owner abandons view creation. */
    public fun discard(id: String): Boolean {
        while (true) {
            val current = bindings.load()
            if (id !in current) return false
            if (bindings.compareAndSet(current, current - id)) return true
        }
    }

    private inline fun updateBindings(transform: (Map<String, KLineChartBinding>) -> Map<String, KLineChartBinding>) {
        while (true) {
            val current = bindings.load()
            if (bindings.compareAndSet(current, transform(current))) return
        }
    }
}
