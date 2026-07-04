package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.WorkerThread
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Renders a GlyphX [Document] (produced by [BlissGlyphXBuilder]) into a
 * [LinearLayout] container, one [SvgCellView] per symbol.
 *
 * ## Threading model
 * - [render] is a **suspending** function; it must be called from a coroutine.
 * - All SVG asset fetches use [BlissSignProvider.getDrawableAsync] (suspend, IO).
 * - All [addView] calls occur on the **Main Thread** in a single batched pass
 *   after all assets are ready → no partial-render flicker.
 * - [cancelRender] / [onDetachedFromWindow] cancel the active render Job to
 *   prevent memory leaks when the host View is recycled.
 *
 * ## Accessibility
 * The container is announced as a Collection; each cell carries
 * CollectionItemInfo (row/col) and a descriptive content description.
 *
 * @param context  Android context (used to create Views).
 * @param provider Pre-initialised [BlissSignProvider].
 * @param scope    [CoroutineScope] whose lifetime matches the host component
 *                 (e.g. viewLifecycleOwner.lifecycleScope).
 */
class BlissRenderer(
    private val context: Context,
    private val provider: BlissSignProvider,
    private val scope: CoroutineScope
) {

    private var renderJob: Job? = null

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Renders [document] into [container], replacing all existing children.
     *
     * Suspends until all SVG drawables are fetched (on IO) and all Views are
     * inflated (on Main).  Cancels any previously running render automatically.
     *
     * Must be called from a coroutine (typically launched on Main).
     *
     * ### BUG-1 fix — render() deadlock
     * The previous implementation did:
     *   renderJob = scope.launch(Dispatchers.Main) { … }
     *   renderJob?.join()
     * If the caller runs on Main, join() blocks the thread waiting for a Job
     * that also needs Main → deadlock / ANR.
     * Fix: use coroutineScope { } so the suspend fun awaits its child naturally,
     * then store the resulting Job only for cancellation purposes (cancelRender).
     */
    suspend fun render(container: LinearLayout, document: Document) {
        // Cancel any stale render before starting a new one
        renderJob?.cancelAndJoin()

        // coroutineScope{} inherits the caller's Job; it suspends until the
        // inner launch completes — no separate join() needed, no deadlock risk.
        coroutineScope {
            renderJob = launch(Dispatchers.Main) {
                container.animate().alpha(0f).setDuration(100).start()

                // BUG-2+3 fix: use the correct tag name from BlissGlyphXBuilder
                val symbols = extractSymbols(document)
                if (symbols.isEmpty()) {
                    container.removeAllViews()
                    container.animate().alpha(1f).setDuration(150).start()
                    return@launch
                }

                // Resolve cell size once before entering IO so we can pass it as size hint
                val cellPx = resolveCellPx(container)

                // ── Fetch all drawables in parallel on IO ─────────────────────────
                val drawables = withContext(Dispatchers.IO) {
                    symbols.map { sym ->
                        async {
                            // BUG-3 fix: attribute names match BlissGlyphXBuilder constants
                            // ATTR_CODE = "code"  (was getAttribute("bciAvId") — always null)
                            // ATTR_MATCH = "match" (was getAttribute("matchType") — always null)
                            val codeAttr = sym.getAttribute(BlissGlyphXBuilder.ATTR_CODE) ?: ""
                            val bciId    = BlissGlyphXBuilder.parseBciAvId(codeAttr)

                            val matchRaw = sym.getAttribute(BlissGlyphXBuilder.ATTR_MATCH) ?: "EXACT"
                            val matchType = runCatching {
                                BlissSymbol.MatchType.valueOf(matchRaw)
                            }.getOrDefault(BlissSymbol.MatchType.UNKNOWN)

                            // getDrawableAsync expects (code: String, size: Float)
                            // Bliss codes are prefixed with "B", size is the display cell in px
                            val drawable = if (bciId > 0) {
                                provider.getDrawableAsync("${BlissGlyphXBuilder.BLISS_PREFIX}$bciId", cellPx.toFloat())
                            } else null

                            Triple(bciId, matchType, drawable)
                        }
                    }.awaitAll()
                }

                // ── Build Views on Main Thread in a single pass ───────────────
                container.removeAllViews()

                val totalCols = symbols.size.coerceAtLeast(1)
                ViewCompat.setAccessibilityDelegate(container, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View, info: AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.setCollectionInfo(
                            CollectionInfoCompat.obtain(1, totalCols, false)
                        )
                    }
                })

                drawables.forEachIndexed { colIdx, (bciId, matchType, drawable) ->
                    val symEl   = symbols[colIdx]
                    val name    = symEl.getAttribute(BlissGlyphXBuilder.ATTR_NAME) ?: "symbol"
                    val indList = parseIndicators(symEl)

                    val cell = SvgCellView(context, bciId, name, matchType, indList)
                    cell.setDrawableResolved(drawable)

                    cell.layoutParams = LinearLayout.LayoutParams(cellPx, cellPx)
                        .also { it.marginEnd = 4.dpToPx(context) }

                    // Accessibility: item info
                    ViewCompat.setAccessibilityDelegate(cell, object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View, info: AccessibilityNodeInfoCompat
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.setCollectionItemInfo(
                                CollectionItemInfoCompat.obtain(0, 1, colIdx, 1, false, false)
                            )
                            val matchLabel = matchType.name.lowercase()
                                .replaceFirstChar { it.uppercase() }
                            info.contentDescription =
                                "Symbol ${colIdx + 1} of $totalCols: $name, $matchLabel match"
                            if (indList.isNotEmpty())
                                info.contentDescription =
                                    "${info.contentDescription}, indicators: ${indList.joinToString()}"
                        }
                    })

                    container.addView(cell)
                }

                container.animate().alpha(1f).setDuration(200).start()
            }
        }
        // renderJob is set above; coroutineScope{} has already awaited completion.
        // We keep the field so cancelRender() can interrupt a future render.
    }

    /** Cancels any in-flight render.  Call from [View.onDetachedFromWindow]. */
    fun cancelRender() {
        renderJob?.cancel()
    }

    // ── SVG export ────────────────────────────────────────────────────────────

    /**
     * Renders all cells onto a [Bitmap] at the given [scale] factor.
     * Must be called from a **worker thread** (heavy Canvas operations).
     */
    @WorkerThread
    fun exportBitmap(container: LinearLayout, scale: Float = 1f): Bitmap {
        require(Looper.myLooper() != Looper.getMainLooper()) {
            "exportBitmap() must not run on the Main Thread"
        }
        val totalW = (container.width  * scale).toInt().coerceAtLeast(1)
        val totalH = (container.height * scale).toInt().coerceAtLeast(1)
        val bmp    = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        container.draw(canvas)
        return bmp
    }

    /**
     * Produces an SVG string with one `<use>` per symbol referencing
     * `#B{bciAvId}` anchors.
     * Pure string building — safe to call from any thread.
     */
    fun exportSvgString(container: LinearLayout, cellSizePx: Int): String {
        val symbols = (0 until container.childCount)
            .mapNotNull { container.getChildAt(it) as? SvgCellView }
        val totalW  = symbols.size * (cellSizePx + 4)
        return buildString {
            appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="$totalW" height="$cellSizePx" viewBox="0 0 $totalW $cellSizePx">""")
            symbols.forEachIndexed { i, cell ->
                val x = i * (cellSizePx + 4)
                appendLine("  <use href=\"#B${cell.bciAvId}\" x=\"$x\" y=\"0\" width=\"$cellSizePx\" height=\"$cellSizePx\"/>")
            }
            appendLine("</svg>")
        }
    }

    // ── internal helpers ──────────────────────────────────────────────────────

    /**
     * BUG-2 fix: the GlyphX schema uses <sign> elements (BlissGlyphXBuilder.TAG_SIGN),
     * NOT <symbol>. The previous getElementsByTagName("symbol") always returned
     * an empty NodeList, so nothing was ever rendered.
     *
     * We search one level deeper: ancientText → line → group → sign.
     * Using TAG_SIGN directly keeps this in sync with the builder constant.
     */
    private fun extractSymbols(document: Document): List<Element> {
        val nodeList = document.getElementsByTagName(BlissGlyphXBuilder.TAG_SIGN)
        return (0 until nodeList.length).map { nodeList.item(it) as Element }
    }

    private fun parseIndicators(element: Element): List<String> {
        // <indicator> elements are siblings of <sign> inside the parent <group>
        val parent   = element.parentNode as? Element ?: return emptyList()
        val indNodes = parent.getElementsByTagName(BlissGlyphXBuilder.TAG_INDICATOR)
        return (0 until indNodes.length).map { (indNodes.item(it) as Element).getAttribute(BlissGlyphXBuilder.ATTR_TYPE) }
    }

    private fun resolveCellPx(container: ViewGroup): Int {
        val available = container.width
            .takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels)
        val count     = (container.childCount + 1).coerceAtLeast(1)
        val minPx     = (MIN_CELL_DP * context.resources.displayMetrics.density).toInt()
        val maxPx     = (MAX_CELL_DP * context.resources.displayMetrics.density).toInt()
        return ((available / count) - 4.dpToPx(context)).coerceIn(minPx, maxPx)
    }

    private fun Int.dpToPx(ctx: Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG          = "BlissRenderer"
        const val DEFAULT_CELL_DP = 72
        private const val MIN_CELL_DP  = 40
        private const val MAX_CELL_DP  = 120
    }

    // ── inner View ────────────────────────────────────────────────────────────

    /**
     * Lightweight custom View that displays one Bliss symbol SVG.
     *
     * - [isDirty] gate: [onDraw] returns immediately when the drawable
     *   has not changed since the last draw (avoids redundant SVG rasterisation).
     * - Focus ring drawn in [onDraw] when [isFocused] or [isSelected].
     * - Indicator overlay drawn via [drawIndicators].
     * - [setDrawableAsync] cancels the previous load Job before starting a new
     *   one to prevent drawable swap races.
     */
    inner class SvgCellView(
        ctx: Context,
        val bciAvId: Int,
        private val symbolName: String,
        private val matchType: BlissSymbol.MatchType,
        private val indicators: List<String>
    ) : View(ctx) {

        private var drawable: android.graphics.drawable.Drawable? = null
        private var isDirty: Boolean = true
        private var loadJob: Job? = null

        private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style   = Paint.Style.STROKE
            strokeWidth = 3f
            color   = Color.parseColor("#01696F")  // --color-primary
        }
        private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80000000")
        }
        private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.FILL_AND_STROKE
        }
        private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (matchType) {
                BlissSymbol.MatchType.UNKNOWN           -> Color.parseColor("#FFD0D0")
                BlissSymbol.MatchType.FALLBACK_CATEGORY -> Color.parseColor("#FFDDB0")
                else                                     -> Color.parseColor("#E8E8E8")
            }
        }

        init {
            isFocusable = true
            setOnFocusChangeListener { _, _ -> isDirty = true; invalidate() }
            contentDescription = "$symbolName (BCI $bciAvId)"
        }

        /** Set drawable resolved synchronously (called from Main Thread render pass). */
        fun setDrawableResolved(d: android.graphics.drawable.Drawable?) {
            drawable = d
            isDirty  = true
            invalidate()
        }

        /**
         * Asynchronously swaps the drawable.  Cancels any pending load before
         * starting a new one — safe to call multiple times (e.g. on recycle).
         *
         * Uses the current view's intrinsic size as the render size hint so the
         * SVG is rasterised at the correct display resolution.
         */
        fun setDrawableAsync(bciId: Int, sizePx: Float = DEFAULT_CELL_DP.toFloat()) {
            loadJob?.cancel()
            loadJob = scope.launch {
                val d = withContext(Dispatchers.IO) {
                    // getDrawableAsync expects (code: String, size: Float)
                    provider.getDrawableAsync("${BlissGlyphXBuilder.BLISS_PREFIX}$bciId", sizePx)
                }
                setDrawableResolved(d)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            loadJob?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            if (!isDirty) return
            isDirty = false

            val w = width.toFloat()
            val h = height.toFloat()
            if (w == 0f || h == 0f) return

            val d = drawable
            if (d != null) {
                d.setBounds(0, 0, width, height)
                d.draw(canvas)
            } else {
                // Fallback: coloured rectangle + "?" text
                canvas.drawRect(0f, 0f, w, h, fallbackPaint)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color     = Color.DKGRAY
                    textSize  = h * 0.35f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("?", w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
            }

            // Indicator overlays
            drawIndicators(canvas, w, h)

            // Focus / selected ring
            if (isFocused || isSelected) {
                canvas.drawRect(RectF(2f, 2f, w - 2f, h - 2f), focusPaint)
            }
        }

        private fun drawIndicators(canvas: Canvas, w: Float, h: Float) {
            val dotR  = w * 0.08f
            val baseY = h - dotR * 2.5f

            indicators.forEachIndexed { i, type ->
                val cx = dotR * 2.5f + i * (dotR * 3f)
                when (type) {
                    "plural" -> {
                        // Filled circle = plural
                        canvas.drawCircle(cx, baseY, dotR, indicatorPaint)
                    }
                    "past" -> {
                        // Left-pointing arrow = past
                        val paint = Paint(indicatorPaint).apply { strokeWidth = dotR * 0.8f }
                        canvas.drawLine(cx + dotR, baseY, cx - dotR, baseY, paint)
                        canvas.drawLine(cx - dotR, baseY, cx, baseY - dotR, paint)
                        canvas.drawLine(cx - dotR, baseY, cx, baseY + dotR, paint)
                    }
                    "future" -> {
                        // Right-pointing arrow = future
                        val paint = Paint(indicatorPaint).apply { strokeWidth = dotR * 0.8f }
                        canvas.drawLine(cx - dotR, baseY, cx + dotR, baseY, paint)
                        canvas.drawLine(cx + dotR, baseY, cx, baseY - dotR, paint)
                        canvas.drawLine(cx + dotR, baseY, cx, baseY + dotR, paint)
                    }
                }
            }
        }
    }
}
