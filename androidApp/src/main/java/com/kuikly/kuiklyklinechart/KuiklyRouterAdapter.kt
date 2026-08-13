package com.kuikly.kuiklyklinechart

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tencent.kuikly.core.render.android.adapter.IKRRouterAdapter
import org.json.JSONObject

internal object KuiklyRouterAdapter : IKRRouterAdapter {
    override fun openPage(context: Context, pageName: String, pageData: JSONObject) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.KEY_PAGE_NAME, pageName)
                .putExtra(MainActivity.KEY_PAGE_DATA, pageData.toString()),
        )
    }

    override fun closePage(context: Context) {
        (context as? Activity)?.finish()
    }
}
