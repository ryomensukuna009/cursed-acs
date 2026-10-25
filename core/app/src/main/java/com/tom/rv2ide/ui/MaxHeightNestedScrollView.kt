/*
 *  This file is part of AndroidCodeStudio (CURSED ACS).
 *  MaxHeightNestedScrollView: enforces a configurable max height limit on NestedScrollView.
 */

package com.tom.rv2ide.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * A NestedScrollView that enforces a maximum height limit.
 * If the inner content is smaller than maxHeightPx, it wraps content naturally.
 * If the inner content exceeds maxHeightPx, its height is clamped to maxHeightPx
 * and the user can smoothly scroll the content vertically.
 */
class MaxHeightNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    // Default maximum height of 180dp
    var maxHeightPx: Int = (180 * context.resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val boundedHeightSpec = if (maxHeightPx > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val targetSize = if (mode == MeasureSpec.UNSPECIFIED || size > maxHeightPx) maxHeightPx else size
            MeasureSpec.makeMeasureSpec(targetSize, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, boundedHeightSpec)
    }
}
