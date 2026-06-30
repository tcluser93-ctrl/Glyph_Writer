package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.blueapps.egyptianwriter.bliss.BlissSymbol.MatchType

/**
 * Standalone View that displays a sequence of Bliss symbols as SVG images
 * placed side-by-side with an English tooltip on tap.
 *
 * Uses [BlissSignProvider] to load SVGs from assets.
 *
 * ## XML usage
 * ```xml
 * <com.blueapps.egyptianwriter.bliss.BlissRenderer
 *     android:id="@+id/blissRenderer"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content" />
 * ```
 *
 * ## Code usage
 * ```kotlin
 * blissRenderer.render(symbols, signProvider)
 * ```
 */
class BlissRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "BlissRenderer"
        const val DEFAULT_SYMBOL_SIZE_DP = 64
    }

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    init {
        addView(container)
        isHorizontalScrollBarEnabled = true
    }

    /**
     * Renders the list of symbols.
     *
     * @param symbols      List of [BlissSymbol] to display
     * @param provider     [BlissSignProvider] used to load SVG drawables
     * @param symbolSizeDp Individual symbol size in dp (default 64)
     */
    fun render(
        symbols: List<BlissSymbol>,
        provider: BlissSignProvider,
        symbolSizeDp: Int = DEFAULT_SYMBOL_SIZE_DP
    ) {
        container.removeAllViews()
        val sizePx = (symbolSizeDp * resources.displayMetrics.density).toInt()
        for (symbol in symbols) {
            container.addView(createCell(symbol, provider, sizePx))
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private fun createCell(
        symbol: BlissSymbol,
        provider: BlissSignProvider,
        sizePx: Int
    ): View {
        val drawable: Drawable? = provider
            .getDrawable("B${symbol.bciAvId}", sizePx.toFloat())
            .also { if (it == null) Log.w(TAG, "No drawable for BCI-AV ${symbol.bciAvId}") }

        return if (drawable != null) {
            SvgCellView(context, drawable, symbol, sizePx).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx + 8.dp, sizePx + 8.dp)
                    .apply { marginEnd = 4.dp }
            }
        } else {
            // Fallback: text chip
            TextView(context).apply {
                text    = symbol.gloss.ifBlank { "?" }
                textSize = 10f
                setTextColor(Color.DKGRAY)
                gravity  = android.view.Gravity.CENTER
                contentDescription = symbol.gloss
                setPadding(4.dp, 4.dp, 4.dp, 4.dp)
                layoutParams = LinearLayout.LayoutParams(sizePx + 8.dp, sizePx + 8.dp)
                    .apply { marginEnd = 4.dp }
                setBackgroundColor(
                    if (symbol.matchType == MatchType.UNKNOWN) 0x33FF0000 else 0x22000000
                )
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    // ── Inner view for a single SVG symbol ────────────────────────────────────

    private class SvgCellView(
        context: Context,
        private val drawable: Drawable,
        private val symbol: BlissSymbol,
        private val sizePx: Int
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = when (symbol.matchType) {
                MatchType.EXACT             -> 0x1100AA44   // transparent green
                MatchType.LEMMA             -> 0x110066CC   // transparent blue
                MatchType.NGRAM             -> 0x11AA6600   // transparent orange
                MatchType.FALLBACK_CATEGORY -> 0x11FF8800   // transparent amber
                MatchType.UNKNOWN           -> 0x22FF0000   // transparent red
            }
        }

        init {
            contentDescription = "[${symbol.matchType.name}] ${symbol.gloss}"
            tooltipText        = "${symbol.gloss} (${symbol.matchType.name})"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint
            )
            drawable.setBounds(4, 4, width - 4, height - 4)
            drawable.draw(canvas)
        }
    }
}
