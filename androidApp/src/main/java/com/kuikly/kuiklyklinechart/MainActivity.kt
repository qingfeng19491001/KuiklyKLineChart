package com.kuikly.kuiklyklinechart

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.adapter.KuiklyRenderAdapterManager
import com.tencent.kuikly.core.render.android.css.ktx.toMap
import com.tencent.kuikly.core.render.android.expand.KuiklyBaseView
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import com.tencent.kuiklybase.kline.view.KLineChartView
import com.tencent.kuiklybase.kline.host.registerKuiklyKLineChart
import org.json.JSONObject

class MainActivity : Activity() {
    private var kuiklyView: KuiklyBaseView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val renderView = KuiklyBaseView(
            this,
            object : KuiklyRenderViewBaseDelegatorDelegate {
                override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
                    kuiklyRenderExport.registerKuiklyKLineChart()
                }
            },
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        kuiklyView = renderView
        setContentView(renderView)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setLightStatusBar(true)
        KuiklyRenderAdapterManager.krRouterAdapter = KuiklyRouterAdapter
        KuiklyRenderAdapterManager.krImageAdapter = KRAssetImageAdapter(applicationContext)
        renderView.onAttach("", pageName, pageData)
    }

    override fun onResume() {
        super.onResume()
        kuiklyView?.onResume()
    }

    override fun onPause() {
        kuiklyView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        kuiklyView?.onDetach()
        kuiklyView = null
        super.onDestroy()

    }

    private fun setLightStatusBar(light: Boolean) {
        val window = window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (light) {
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                )
            } else {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            }
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = if (light) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    private val pageName: String
        get() = intent.getStringExtra(KEY_PAGE_NAME).orEmpty().ifBlank { ROUTER_PAGE }

    private val pageData: Map<String, Any>
        get() = intent.getStringExtra(KEY_PAGE_DATA)?.let { JSONObject(it).toMap() } ?: emptyMap()

    companion object {
        internal const val KEY_PAGE_NAME = "pageName"
        internal const val KEY_PAGE_DATA = "pageData"
        private const val ROUTER_PAGE = "router"
    }
}
