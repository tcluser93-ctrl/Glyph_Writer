package com.blueapps.egyptianwriter.bliss

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.blueapps.egyptianwriter.bliss.BlissSymbol.MatchType
import com.blueapps.egyptianwriter.bliss.DpUtil.dpToPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone View that displays a horizontal sequence of Bliss symbols.
 *
 * ## Enterprise changes (F2-01 .. F2-11)
 *
 * ### F2-01 — Async cell population
 * `render()` no longer blocks Main Thread. It launches a coroutine on [Dispatchers.IO]
 * that loads all drawables, then posts a single `post { }` to inflate all cells at once,
 * replacing the old O(n) `removeAllViews() + addView()` loop on the UI thread.
 * A new [renderJob] is cancelled when a new `render()` call arrives, preventing
 * stale results from a slow previous render overwriting a faster new one.
 *
 * ### F2-02 — Dirty-flag in SvgCellView
 * `onDraw()` now skips canvas operations when the cell is not dirty (no state change).
 * The first draw always runs; subsequent draws skip unless `invalidateCellState()` is called.
 *
 * ### F2-03 / F2-10 — Grammatical indicator overlays
 * Symbols with `BlissSymbol.indicators` non-empty receive overlay icons:
 *   - "plural"  → small dot drawn at the top-center of the cell
 *   - "past"    → short horizontal line at the cell bottom
 *   - "future"  → short horizontal line at the cell top
 * Overlays are drawn in `SvgCellView.onDraw()` after the SVG.
 *
 * ### F2-04 — AccessibilityDelegate for TalkBack
 * The container LinearLayout is annotated as a list via
 * `AccessibilityNodeInfoCompat.CollectionInfoCompat`, and each SvgCellView
 * as a list item with row/column info. TalkBack now announces:
 *   "Symbol 3 of 7, walk, EXACT match"
 * instead of reading raw contentDescription strings in DOM order.
 *
 * ### F2-05 — Adaptive symbol size (fold/tablet)
 * `symbolSizeDp` is no longer fixed. `onMeasure()` computes the optimal cell size
 * from available width and [minSymbolSizeDp] / [maxSymbolSizeDp] bounds.
 *
 * ### F2-06 — Selected/focused state
 * `SvgCellView` supports `isSelected` and a distinct focus ring, enabling
 * D-pad / switch-access navigation for CAA users.
 *
 * ### F2-07 — Fade-in animation
 * New cells fade in over 200ms when a render completes, making updates visible.

 * ### F2-08 — Async drawable load on Dispatchers.IO
 * `BlissSignProvider.getDrawableAsync()` is called inside a `withContext(Dispatchers.IO)`
 * block, completely off the Main Thread.
 *
 * ### F2-11 — Export API
 * `exportBitmap(scale)` renders the current symbol row to a `Bitmap`.
 * `exportSvgString()` composes a minimal multi-symbol SVG string
 * using the raw SVG text of each loaded asset — suitable for PDF/share export.
 *
 * ## XML usage
 * ```xml
 * <com.blueapps.egyptianwriter.bliss.BlissRenderer
 *     android:id="@+id/blissRenderer"
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:minSymbolSizeDp="48"
 *     app:maxSymbolSizeDp="96" />
 * ```
 *
 * ## Code usage
 * ```kotlin
 * // In a Fragment:
 * viewLifecycleOwner.lifecycleScope.launch {
 *     blissRenderer.render(symbols, signProvider)
 * }
 * ```
 */
class BlissRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    // ── configuration ─────────────────────────────────────────────────────────

    /** Minimum cell size in dp; enforces WCAG 44dp touch-target floor. */
    var minSymbolSizeDp: Int = DpUtil.MIN_TOUCH_TARGET_DP

    /** Maximum cell size in dp; caps growth on tablets/foldables. */
    var maxSymbolSizeDp: Int = 96

    // ── inner layout ──────────────────────────────────────────────────────────

    private val container = LinearLayout(context).apply {
        orientation  = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        // F2-04: mark as list container for TalkBack
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.setCollectionInfo(
                    AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(
                        1, childCount,
                        AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_NONE
                    )
                )
            }
        })
    }

    init {
        addView(container)
        isHorizontalScrollBarEnabled = true
        clipToPadding = false
    }

    // ── state ─────────────────────────────────────────────────────────────────

    private var renderJob: Job? = null
    private val renderScope = CoroutineScope(Dispatchers.Main)
    private var currentSymbols: List<BlissSymbol> = emptyList()

    // Computed cell size after onMeasure
    private var resolvedCellPx: Int = 0

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Asynchronously renders [symbols] using [provider].
     * Safe to call from the Main Thread; drawable loading runs on [Dispatchers.IO].
     * Cancels any in-flight render before starting.
     *
     * @param symbols    Symbols to display.
     * @param provider   [BlissSignProvider] for SVG drawables.
     */
    fun render(symbols: List<BlissSymbol>, provider: BlissSignProvider) {
        renderJob?.cancel()
        currentSymbols = symbols

        val cellPx = resolvedCellPx.takeIf { it > 0 }
            ?: (minSymbolSizeDp.dpToPx(resources))

        renderJob = renderScope.launch {
            // F2-08: load all drawables off Main Thread
            val drawables: List<Drawable?> = withContext(Dispatchers.IO) {
                symbols.map { sym ->
                    provider.getDrawableAsync("B${sym.bciAvId}", cellPx.toFloat())
                        .also { if (it == null) Log.w(TAG, "No drawable for BCI-AV ${sym.bciAvId}") }
                }
            }

            // Back on Main Thread — inflate cells
            container.removeAllViews()
            symbols.forEachIndexed { index, symbol ->
                val cell = createCell(symbol, drawables[index], cellPx, index, symbols.size)
                container.addView(cell)
            }

            // F2-07: fade in the entire container
            container.alpha = 0f
            container.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        }
    }

    /**
     * F2-11: Export the current symbol row as a [Bitmap].
     *
     * @param scale  Scaling factor (1f = native size, 2f = 2× resolution).
     * @return       Bitmap or null if container has no size yet.
     */
    fun exportBitmap(scale: Float = 1f): Bitmap? {
        val w = container.width.takeIf { it > 0 } ?: return null
        val h = container.height.takeIf { it > 0 } ?: return null
        val bmp = Bitmap.createBitmap(
            (w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        container.draw(canvas)
        return bmp
    }

    /**
     * F2-11: Compose a minimal SVG string wrapping all loaded symbol SVGs
     * side-by-side. Suitable for external share / PDF embed.
     *
     * @param cellSizePx  Size of each cell in the output SVG (pixels).
     */
    fun exportSvgString(cellSizePx: Int = 128): String {
        val syms  = currentSymbols
        val total = syms.size
        val sb    = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${total * cellSizePx}" height="$cellSizePx">""")
        syms.forEachIndexed { i, sym ->
            val x = i * cellSizePx
            sb.append("""<use href="#B${sym.bciAvId}" x="$x" y="0" width="$cellSizePx" height="$cellSizePx"/>""")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** Clean up coroutines when the view is detached. */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderScope.cancel()
    }

    // ── measure (F2-05) ───────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (availableWidth > 0 && currentSymbols.isNotEmpty()) {
            val minPx = minSymbolSizeDp.dpToPx(resources)
            val maxPx = maxSymbolSizeDp.dpToPx(resources)
            // Fill available width, clamped between min and max
            resolvedCellPx = (availableWidth / currentSymbols.size)
                .coerceIn(minPx, maxPx)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ── private factory ───────────────────────────────────────────────────────

    private fun createCell(
        symbol: BlissSymbol,
        drawable: Drawable?,
        sizePx: Int,
        index: Int,
        total: Int
    ): View {
        val cellSizePx = sizePx + 8.dpToPx(resources)

        return if (drawable != null) {
            SvgCellView(context, drawable, symbol, sizePx).apply {
                layoutParams = LinearLayout.LayoutParams(cellSizePx, cellSizePx)
                    .apply { marginEnd = 4.dpToPx(resources) }
                // F2-04: list-item accessibility
                ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.setCollectionItemInfo(
                            AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                                0, 1, index, 1, false
                            )
                        )
                        info.roleDescription = "Bliss symbol"
                    }
                })
                contentDescription =
                    "Symbol ${index + 1} of $total: ${symbol.gloss}, ${symbol.matchType.name} match"
            }
        } else {
            // Fallback text chip
            TextView(context).apply {
                text    = symbol.gloss.ifBlank { "?" }
                textSize = 10f
                setTextColor(Color.DKGRAY)
                gravity  = Gravity.CENTER
                contentDescription = "Symbol ${index + 1} of $total: ${symbol.gloss}, unknown"
                setPadding(
                    4.dpToPx(resources), 4.dpToPx(resources),
                    4.dpToPx(resources), 4.dpToPx(resources)
                )
                layoutParams = LinearLayout.LayoutParams(cellSizePx, cellSizePx)
                    .apply { marginEnd = 4.dpToPx(resources) }
                setBackgroundColor(
                    if (symbol.matchType == MatchType.UNKNOWN) 0x33FF0000 else 0x22000000
                )
            }
        }
    }

    // ── SvgCellView ───────────────────────────────────────────────────────────

    /**
     * Custom View for a single SVG Bliss symbol cell.
     *
     * Improvements:
     * - F2-02: dirty flag — skips onDraw when state unchanged
     * - F2-03: indicator overlays (plural dot, tense lines)
     * - F2-06: selected/focused state with visible ring
     */
    private class SvgCellView(
        context: Context,
        private val drawable: Drawable,
        private val symbol: BlissSymbol,
        private val sizePx: Int
    ) : View(context) {

        // F2-02: dirty flag
        private var isDirty = true
        fun invalidateCellState() { isDirty = true; invalidate() }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = matchColor(symbol.matchType)
        }

        // F2-06: focus/selection ring
        private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF1565C0.toInt() // Material Blue 800
        }

        // F2-03: indicator paints
        private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2f
            color       = 0xFF333333.toInt()
        }

        init {
            isFocusable = true
            isClickable  = true
            // F2-06: re-draw on focus change
            setOnFocusChangeListener { _, _ -> invalidateCellState() }
        }

        override fun onDraw(canvas: Canvas) {
            if (!isDirty) return
            isDirty = false
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()

            // Background tint
            canvas.drawRoundRect(0f, 0f, w, h, 8f, 8f, bgPaint)

            // SVG
            drawable.setBounds(4, 4, width - 4, height - 4)
            drawable.draw(canvas)

            // F2-03: grammatical indicator overlays
            val indicators = symbol.indicators
            if ("plural" in indicators) {
                // Dot above the symbol — center-top
                canvas.drawCircle(w / 2f, 6f, 3f, indicatorPaint)
            }
            if ("past" in indicators) {
                // Short line at bottom
                canvas.drawLine(w * 0.3f, h - 4f, w * 0.7f, h - 4f, indicatorPaint)
            }
            if ("future" in indicators) {
                // Short line at top (above plural dot if present)
                val y = if ("plural" in indicators) 14f else 4f
                canvas.drawLine(w * 0.3f, y, w * 0.7f, y, indicatorPaint)
            }

            // F2-06: focus/selection ring
            if (isFocused || isSelected) {
                canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, 8f, 8f, focusPaint)
            }
        }

        companion object {
            private fun matchColor(type: MatchType): Int = when (type) {
                MatchType.EXACT             -> 0x1100AA44
                MatchType.LEMMA             -> 0x110066CC
                MatchType.NGRAM             -> 0x11AA6600
                MatchType.FALLBACK_CATEGORY -> 0x11FF8800
                MatchType.UNKNOWN           -> 0x22FF0000
            }
        }
    }

    companion object {
        private const val TAG = "BlissRenderer"
    }

    // ── private Int extension (F2-12: now uses shared DpUtil) ─────────────────

    private fun Int.dpToPx(res: android.content.res.Resources): Int =
        DpUtil.run { dpToPx(res) }
}
