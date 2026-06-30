package com.blueapps.egyptianwriter.bliss

import android.content.res.Resources
import android.util.TypedValue

/**
 * Shared dp/px conversion utilities.
 * Avoids defining the same `Int.dp` extension in every class.
 *
 * Usage:
 *   val sizePx = 64.dpToPx(resources)
 *   val sizeDp = 128f.pxToDp(resources)
 */
object DpUtil {
    fun Int.dpToPx(res: Resources): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), res.displayMetrics)
            .toInt()

    fun Float.pxToDp(res: Resources): Float =
        this / res.displayMetrics.density

    /** Returns dp as px using the screen density of the given Resources. */
    fun Int.dp(res: Resources): Int = dpToPx(res)

    /** Minimum touch target size per WCAG / Android accessibility guidelines: 44dp. */
    const val MIN_TOUCH_TARGET_DP = 44
}
