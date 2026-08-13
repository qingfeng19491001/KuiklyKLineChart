package com.tencent.kuiklybase.kline.view

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KLineChartBindingRegistryTest {
    @Test
    fun bindingCanBeConsumedExactlyOnce() {
        val source = StaticKLineDataSource(emptyList())
        val controller = KLineChartController()
        val id = KLineChartBindingRegistry.register(source, controller)

        val binding = KLineChartBindingRegistry.take(id)

        assertSame(source, binding?.dataSource)
        assertSame(controller, binding?.controller)
        assertNull(KLineChartBindingRegistry.take(id))
    }

    @Test
    fun abandonedBindingCanBeDiscarded() {
        val id = KLineChartBindingRegistry.register(StaticKLineDataSource(emptyList()), KLineChartController())

        assertTrue(KLineChartBindingRegistry.discard(id))
        assertFalse(KLineChartBindingRegistry.discard(id))
        assertNull(KLineChartBindingRegistry.take(id))
    }
}
