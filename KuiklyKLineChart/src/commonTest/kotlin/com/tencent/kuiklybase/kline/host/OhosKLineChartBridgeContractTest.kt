package com.tencent.kuiklybase.kline.host

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuiklybase.kline.controller.KLineChartController
import com.tencent.kuiklybase.kline.data.StaticKLineDataSource
import com.tencent.kuiklybase.kline.view.KLineChartBindingRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OhosKLineChartBridgeContractTest {
    @Test
    fun bindingIdConsumesTheRegisteredCommonBinding() {
        val id = KLineChartBindingRegistry.register(
            StaticKLineDataSource(emptyList()),
            KLineChartController(),
        )
        val bridge = OhosKLineChartBridge()

        assertTrue(bridge.setProp("bindingId", id))
        assertNull(KLineChartBindingRegistry.take(id))

        bridge.dispose()
    }

    @Test
    fun paneYAxisConfigurationSurvivesStateExport() {
        val bridge = OhosKLineChartBridge()
        val pane =
            """{"id":"price","kind":"price","order":0,"weight":3.0,"minHeight":120.0,"yAxes":[{"id":"price-main","minValue":10.0,"maxValue":20.0,"mode":"normal","autoScale":false},{"id":"price-percent","minValue":-5.0,"maxValue":5.0,"mode":"percentage","referenceValue":15.0,"autoScale":true}]}"""

        bridge.call("setPane", pane)
        val state = JSONObject(bridge.call("exportState", "{}"))
        val pricePane = state.optJSONArray("panes")
            ?.let { panes -> (0 until panes.length()).mapNotNull(panes::optJSONObject) }
            ?.first { it.optString("id") == "price" }
        val axes = pricePane?.optJSONArray("yAxes")

        assertEquals(2, axes?.length())
        assertEquals("price-main", axes?.optJSONObject(0)?.optString("id"))
        assertEquals("percentage", axes?.optJSONObject(1)?.optString("mode"))
        assertEquals(15.0, axes?.optJSONObject(1)?.optDouble("referenceValue"))

        bridge.dispose()
    }
}
