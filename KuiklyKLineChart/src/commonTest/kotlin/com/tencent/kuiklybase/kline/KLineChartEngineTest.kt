package com.tencent.kuiklybase.kline

import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import kotlin.test.Test
import kotlin.test.assertFailsWith

class KLineChartEngineTest {
    @Test
    fun disposeDetachesController() {
        val controller = KLineChartController()
        val engine = KLineChartEngine(
            dataSource = StaticKLineDataSource(emptyList()),
            controller = controller,
        )

        engine.dispose()

        assertFailsWith<IllegalStateException> { controller.exportState() }
    }
}
