package com.tencent.kuiklybase.kline.demo

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.expand.KuiklyBaseView
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import com.tencent.kuiklybase.kline.view.KLineChartView

class MainActivity : Activity() {
    private var kuiklyView: KuiklyBaseView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Run programmatically-applied window styling before decor creation
        window.statusBarColor = Color.WHITE
        setLightStatusBar(true)

        super.onCreate(savedInstanceState)

        val renderView = KuiklyBaseView(
            this,
            object : KuiklyRenderViewBaseDelegatorDelegate {
                override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
                    with(kuiklyRenderExport) {
                        renderViewExport(KLineChartView.VIEW_NAME, { context ->
                            AndroidKLineChartView(context)
                        })
                    }
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
        renderView.onAttach("", PAGE_NAME, emptyMap())
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

    private companion object {
        const val PAGE_NAME = "KuiklyKLineDemo"
    }
}
