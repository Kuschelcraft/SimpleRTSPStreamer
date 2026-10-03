package com.kuschelcraft.simplertspstreamer

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Frame that keeps a fixed aspect ratio, never taller than [maxHeightFraction] of the screen. */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var aspect: Float = 16f / 9f
        set(value) {
            if (value > 0f && value != field) {
                field = value
                requestLayout()
            }
        }

    var maxHeightFraction: Float = 0.45f
        set(value) {
            if (value != field) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = (resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        var w = availableWidth
        var h = (w / aspect).toInt()
        if (h > maxHeight) {
            h = maxHeight
            w = (h * aspect).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
