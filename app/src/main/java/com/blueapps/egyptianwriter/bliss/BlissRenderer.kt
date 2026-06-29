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
import com.blueapps.egyptianwriter.bliss.BlissSymbol.MatchType

/**
 * BlissRenderer — View standalone che mostra una sequenza di simboli Bliss
 * come SVG affiancati, con tooltip del nome inglese al tap.
 *
 * Usa BlissSignProvider per caricare gli SVG da assets.
 *
 * Utilizzo XML:
 *   <com.blueapps.egyptianwriter.bliss.BlissRenderer
 *       android:id="@+id/blissRenderer"
 *       android:layout_width="match_parent"
 *       android:layout_height="wrap_content" />
 *
 * Utilizzo codice:
 *   blissRenderer.render(symbols, signProvider)
 *
 * Questa view è indipendente da ThothView ed è utile per:
 *  - Anteprima inline nel fragment di traduzione
 *  - Export PNG/SVG del risultato
 *  - Fallback se ThothView non supporta setSignProvider
 */
class BlissRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "BlissRenderer"
        const val DEFAULT_SYMBOL_SIZE_DP = 64
        const val UNKNOWN_COLOR = Color.LTGRAY
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
     * Renderizza la lista di simboli.
     *
     * @param symbols       Lista di BlissSymbol da visualizzare
     * @param provider      BlissSignProvider (per SVG)
     * @param symbolSizeDp  Dimensione singolo simbolo in dp
     */
    fun render(
        symbols: List<BlissSymbol>,
        provider: BlissSignProvider,
        symbolSizeDp: Int = DEFAULT_SYMBOL_SIZE_DP
    ) {
        container.removeAllViews()

        val density = resources.displayMetrics.density
        val sizePx = (symbolSizeDp * density).toInt()

        for (symbol in symbols) {
            val cell = createCell(symbol, provider, sizePx)
            container.addView(cell)
        }
    }

    // -------------------------------------------------------------------------
    // Privati
    // -------------------------------------------------------------------------

    private fun createCell(
        symbol: BlissSymbol,
        provider: BlissSignProvider,
        sizePx: Int
    ): View {
        val drawable: Drawable? = if (symbol.bciAvId != null) {
            provider.getDrawable("B${symbol.bciAvId}", sizePx.toFloat())
        } else null

        return if (drawable != null) {
            // Vista SVG
            val v = SvgCellView(context, drawable, symbol, sizePx)
            v.layoutParams = LinearLayout.LayoutParams(sizePx + 8.dp, sizePx + 8.dp).apply {
                marginEnd = 4.dp
            }
            v
        } else {
            // Fallback testo
            val tv = android.widget.TextView(context).apply {
                text = symbol.gloss.ifBlank { "?" }
                textSize = 10f
                setTextColor(Color.DKGRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(4.dp, 4.dp, 4.dp, 4.dp)
                layoutParams = LinearLayout.LayoutParams(sizePx + 8.dp, sizePx + 8.dp).apply {
                    marginEnd = 4.dp
                }
                setBackgroundColor(if (symbol.matchType == MatchType.UNKNOWN) 0x33FF0000 else 0x22000000)
            }
            tv
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------------------------
    // Inner view per singolo simbolo SVG
    // -------------------------------------------------------------------------

    private class SvgCellView(
        context: Context,
        private val drawable: Drawable,
        private val symbol: BlissSymbol,
        private val sizePx: Int
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = when (symbol.matchType) {
                MatchType.EXACT   -> 0x1100AA44  // verde trasparente
                MatchType.LEMMA   -> 0x110066CC  // blu trasparente
                MatchType.NGRAM   -> 0x11AA6600  // arancio trasparente
                MatchType.UNKNOWN -> 0x22FF0000  // rosso trasparente
            }
        }

        init {
            contentDescription = symbol.gloss
            tooltipText = "[${symbol.matchType.name}] ${symbol.gloss}"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Sfondo colorato in base al tipo di match
            canvas.drawRoundRect(
                0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bgPaint
            )
            // Drawable SVG centrato
            drawable.setBounds(4, 4, width - 4, height - 4)
            drawable.draw(canvas)
        }
    }
}
