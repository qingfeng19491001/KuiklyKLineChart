package com.kuikly.kuiklyklinechart

import android.content.Context
import android.graphics.drawable.Drawable
import com.tencent.kuikly.core.render.android.KuiklyRenderViewContext
import com.tencent.kuikly.core.render.android.adapter.HRImageLoadOption
import com.tencent.kuikly.core.render.android.adapter.IKRImageAdapter

internal class KRAssetImageAdapter(private val context: Context) : IKRImageAdapter {
    override fun fetchDrawable(
        imageLoadOption: HRImageLoadOption,
        callback: (Drawable?) -> Unit,
    ) {
        if (!imageLoadOption.isAssets()) {
            callback(null)
            return
        }
        val path = imageLoadOption.src.substring(HRImageLoadOption.SCHEME_ASSETS.length)
        val drawable = runCatching {
            context.assets.open(path).use { Drawable.createFromStream(it, path) }
        }.getOrNull()
        callback(drawable)
    }

    override fun getDrawableWidth(
        kuiklyRenderViewContext: KuiklyRenderViewContext,
        drawable: Drawable,
    ): Float = drawable.intrinsicWidth.toFloat()

    override fun getDrawableHeight(
        kuiklyRenderViewContext: KuiklyRenderViewContext,
        drawable: Drawable,
    ): Float = drawable.intrinsicHeight.toFloat()
}
