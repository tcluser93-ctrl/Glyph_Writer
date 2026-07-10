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
 * ## Patch 7 — BlissRenderAttachment overlay support
 * [renderWithAttachments] is a new entry point that accepts a [ComposedBlissWord]
 * and draws indicator overlays directly from [BlissRenderAttachment] metadata,
 * using [BlissRenderAttachment.DEFAULT_OVERLAY_Y_OFFSET_PX] scaled by display
 * density.  The legacy [render] path is unchanged for backward compatibility.
 *
 * ## Patch 14 — ViewGroup widening
 * [renderWithAttachments] now accepts [ViewGroup] instead of [LinearLayout],
 * resolving the ClassCastException when called with [FrameLayout] (from
 * [MixedBlissRowView.svgContainerFor]) or [FlexboxLayout] (symbolContainer).
 * [ViewGroup.MarginLayoutParams] replaces [LinearLayout.LayoutParams] for cells.
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
     */
    suspend fun render(container: LinearLayout, document: Document) {
        renderJob?.cancelAndJoin()
        coroutineScope {
            renderJob = launch(Dispatchers.Main) {
                container.animate().alpha(0f).setDuration(100).start()

                val symbols = extractSymbols(document)
                if (symbols.isEmpty()) {
                    container.removeAllViews()
                    container.animate().alpha(1f).setDuration(150).start()
                    return@launch
                }

                val cellPx = resolveCellPx(container)

                val drawables = withContext(Dispatchers.IO) {
                    symbols.map { sym ->
                        async {
                            val codeAttr = sym.getAttribute(BlissGlyphXBuilder.ATTR_CODE) ?: ""
                            val bciId    = BlissGlyphXBuilder.parseBciAvId(codeAttr)

                            val matchRaw = sym.getAttribute(BlissGlyphXBuilder.ATTR_MATCH) ?: "EXACT"
                            val matchType = runCatching {
                                BlissSymbol.MatchType.valueOf(matchRaw)
                            }.getOrDefault(BlissSymbol.MatchType.UNKNOWN)

                            val drawable = if (bciId > 0) {
                                provider.getDrawableAsync("${BlissGlyphXBuilder.BLISS_PREFIX}$bciId", cellPx.toFloat())
                            } else null

                            Triple(bciId, matchType, drawable)
                        }
                    }.awaitAll()
                }

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
    }

    /**
     * Patch 7 — structured render path.  Patch 14 — ViewGroup widening.
     *
     * Renders a [ComposedBlissWord] into [container], drawing one [SvgCellView]
     * per [ResolvedBlissComponent] and positioning indicator SVG overlays using
     * the [BlissRenderAttachment] metadata attached to each component.
     *
     * Overlay indicators (combining BCI symbols, e.g. tense dots) are drawn
     * above the base glyph at [BlissRenderAttachment.yOffsetPx] scaled by
     * display density.  Linear modifiers are placed as adjacent cells after
     * the base glyph.
     *
     * This path does NOT replace [render]; callers that already use the GlyphX
     * Document pipeline continue to work unchanged.  New callers (e.g.
     * BlissViewModel tier-3g result) should prefer [renderWithAttachments].
     *
     * ## Patch 14
     * Parameter type widened from [LinearLayout] to [ViewGroup] so the method
     * accepts both [FrameLayout] (from [MixedBlissRowView.svgContainerFor]) and
     * [FlexboxLayout] (symbolContainer) without a ClassCastException.
     * [ViewGroup.MarginLayoutParams] is used instead of [LinearLayout.LayoutParams]
     * since [marginEnd] is available on [ViewGroup.MarginLayoutParams].
     *
     * @param container Target [ViewGroup] (cleared before adding new cells).
     * @param composed  Structured output from [BlissSemanticComposer.composeStructured].
     */
    suspend fun renderWithAttachments(container: ViewGroup, composed: ComposedBlissWord) {
        renderJob?.cancelAndJoin()
        val density = context.resources.displayMetrics.density

        coroutineScope {
            renderJob = launch(Dispatchers.Main) {
                container.animate().alpha(0f).setDuration(100).start()

                if (composed.components.isEmpty()) {
                    container.removeAllViews()
                    container.animate().alpha(1f).setDuration(150).start()
                    return@launch
                }

                val cellPx = resolveCellPx(container)

                // Fetch base glyphs + overlay drawables in parallel on IO
                data class CellData(
                    val component: ResolvedBlissComponent,
                    val baseDrawable: android.graphics.drawable.Drawable?,
                    val overlayDrawables: List<Pair<BlissRenderAttachment, android.graphics.drawable.Drawable?>>
                )

                val cellDataList = withContext(Dispatchers.IO) {
                    composed.components.map { component ->
                        async {
                            val baseDrawable = provider.getDrawableAsync(
                                "${BlissGlyphXBuilder.BLISS_PREFIX}${component.symbol.bciAvId}",
                                cellPx.toFloat()
                            )

                            val overlayDrawables = component.renderAttachments
                                .filter { it.isOverlay }
                                .sortedBy { it.priority }
                                .map { attachment ->
                                    val d = if (attachment.bciIndicatorId > 0) {
                                        provider.getDrawableAsync(
                                            "${BlissGlyphXBuilder.BLISS_PREFIX}${attachment.bciIndicatorId}",
                                            (cellPx * OVERLAY_SIZE_RATIO).toFloat()
                                        )
                                    } else null
                                    attachment to d
                                }

                            CellData(component, baseDrawable, overlayDrawables)
                        }
                    }.awaitAll()
                }

                container.removeAllViews()

                val totalCols = cellDataList.size.coerceAtLeast(1)
                ViewCompat.setAccessibilityDelegate(container, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View, info: AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.setCollectionInfo(CollectionInfoCompat.obtain(1, totalCols, false))
                    }
                })

                cellDataList.forEachIndexed { colIdx, cellData ->
                    val component = cellData.component

                    // Collect indicator names from overlay attachments for legacy drawIndicators
                    val indNames = component.renderAttachments
                        .filter { it.isOverlay }
                        .mapNotNull { att -> bciIdToIndicatorName(att.bciIndicatorId) }

                    val cell = SvgCellView(
                        ctx       = context,
                        bciAvId   = component.symbol.bciAvId,
                        symbolName = component.lemma,
                        matchType  = BlissSymbol.MatchType.SEMANTIC,
                        indicators = indNames,
                        // Overlay drawables passed for structured SVG overlay rendering
                        overlays   = cellData.overlayDrawables
                            .map { (att, d) ->
                                OverlaySpec(
                                    drawable    = d,
                                    yOffsetPx   = att.yOffsetPx * density,
                                    xOffsetPx   = att.xOffsetPx * density,
                                    sizeFraction = OVERLAY_SIZE_RATIO
                                )
                            }
                    )
                    cell.setDrawableResolved(cellData.baseDrawable)
                    // Patch 14: ViewGroup.MarginLayoutParams (marginEnd available, no LinearLayout cast)
                    cell.layoutParams = ViewGroup.MarginLayoutParams(cellPx, cellPx)
                        .also { it.marginEnd = 4.dpToPx(context) }

                    ViewCompat.setAccessibilityDelegate(cell, object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View, info: AccessibilityNodeInfoCompat
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.setCollectionItemInfo(
                                CollectionItemInfoCompat.obtain(0, 1, colIdx, 1, false, false)
                            )
                            info.contentDescription = buildString {
                                append("Symbol ${colIdx + 1} of $totalCols: ${component.lemma}, Semantic match")
                                if (indNames.isNotEmpty())
                                    append(", indicators: ${indNames.joinToString()}")
                            }
                        }
                    })

                    container.addView(cell)

                    // Linear modifiers: add as separate adjacent cells
                    component.renderAttachments
                        .filter { !it.isOverlay }
                        .sortedBy { it.priority }
                        .forEach { modifier ->
                            if (modifier.bciIndicatorId > 0) {
                                val modCell = SvgCellView(
                                    ctx        = context,
                                    bciAvId    = modifier.bciIndicatorId,
                                    symbolName = "modifier",
                                    matchType  = BlissSymbol.MatchType.SEMANTIC,
                                    indicators = emptyList()
                                )
                                modCell.setDrawableAsync(modifier.bciIndicatorId, (cellPx * MODIFIER_SIZE_RATIO).toFloat())
                                // Patch 14: ViewGroup.MarginLayoutParams invece di LinearLayout.LayoutParams
                                modCell.layoutParams = ViewGroup.MarginLayoutParams(
                                    (cellPx * MODIFIER_SIZE_RATIO).toInt(),
                                    (cellPx * MODIFIER_SIZE_RATIO).toInt()
                                ).also { it.marginEnd = 2.dpToPx(context) }
                                container.addView(modCell)
                            }
                        }
                }

                container.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    /** Cancels any in-flight render.  Call from [View.onDetachedFromWindow]. */
    fun cancelRender() {
        renderJob?.cancel()
    }

    // ── SVG export ───────────────────────────────────────────────────────────────

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

    // ── internal helpers ───────────────────────────────────────────────────────────

    private fun extractSymbols(document: Document): List<Element> {
        val nodeList = document.getElementsByTagName(BlissGlyphXBuilder.TAG_SIGN)
        return (0 until nodeList.length).map { nodeList.item(it) as Element }
    }

    private fun parseIndicators(element: Element): List<String> {
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

    /**
     * Maps a BCI combining-indicator id to the Bliss indicator string name.
     * Returns null for ids not currently mapped (silently dropped).
     */
    private fun bciIdToIndicatorName(bciIndicatorId: Int): String? = when (bciIndicatorId) {
        9011 -> "plural"
        9007 -> "past"
        9008 -> "future"
        else -> null
    }

    private fun Int.dpToPx(ctx: Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG               = "BlissRenderer"
        const val DEFAULT_CELL_DP           = 72
        private const val MIN_CELL_DP       = 40
        private const val MAX_CELL_DP       = 120
        /** Overlay indicators are rendered at this fraction of the base cell size. */
        const val OVERLAY_SIZE_RATIO        = 0.35f
        /** Linear modifiers (non-overlay) rendered at this fraction of the base cell. */
        const val MODIFIER_SIZE_RATIO       = 0.55f
    }

    // ── data classes ───────────────────────────────────────────────────────────────

    /**
     * Resolved overlay spec passed to [SvgCellView] for density-aware SVG overlay drawing.
     *
     * @param drawable     The indicator SVG drawable (may be null if asset not found).
     * @param yOffsetPx    Y offset in **device pixels** (scaled from [BlissRenderAttachment.yOffsetPx]).
     * @param xOffsetPx    X offset in device pixels.
     * @param sizeFraction Size of the overlay as a fraction of the host cell size.
     */
    data class OverlaySpec(
        val drawable: android.graphics.drawable.Drawable?,
        val yOffsetPx: Float,
        val xOffsetPx: Float,
        val sizeFraction: Float
    )

    // ── inner View ───────────────────────────────────────────────────────────────

    /**
     * Lightweight custom View that displays one Bliss symbol SVG.
     *
     * - [isDirty] gate: [onDraw] returns immediately when the drawable
     *   has not changed since the last draw.
     * - Focus ring drawn in [onDraw] when [isFocused] or [isSelected].
     * - Indicator overlay drawn via [drawIndicators] (legacy named indicators).
     * - SVG overlay drawables drawn via [drawOverlays] (Patch 7 structured path).
     * - [setDrawableAsync] cancels the previous load Job before starting a new one.
     */
    inner class SvgCellView(
        ctx: Context,
        val bciAvId: Int,
        private val symbolName: String,
        private val matchType: BlissSymbol.MatchType,
        private val indicators: List<String>,
        private val overlays: List<OverlaySpec> = emptyList()
    ) : View(ctx) {

        private var drawable: android.graphics.drawable.Drawable? = null
        private var isDirty: Boolean = true
        private var loadJob: Job? = null

        private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            strokeWidth = 3f
            color       = Color.parseColor("#01696F")
        }
        private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.WHITE
            strokeWidth = 2f
            style       = Paint.Style.FILL_AND_STROKE
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

        fun setDrawableResolved(d: android.graphics.drawable.Drawable?) {
            drawable = d
            isDirty  = true
            invalidate()
        }

        fun setDrawableAsync(bciId: Int, sizePx: Float = DEFAULT_CELL_DP.toFloat()) {
            loadJob?.cancel()
            loadJob = scope.launch {
                val d = withContext(Dispatchers.IO) {
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
                canvas.drawRect(0f, 0f, w, h, fallbackPaint)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color     = Color.DKGRAY
                    textSize  = h * 0.35f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("?", w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
            }

            // Patch 7: draw SVG overlay drawables (density-scaled, from BlissRenderAttachment)
            if (overlays.isNotEmpty()) drawOverlays(canvas, w, h)

            // Legacy named-indicator drawing (plural/past/future dots & arrows)
            if (indicators.isNotEmpty()) drawIndicators(canvas, w, h)

            if (isFocused || isSelected) {
                canvas.drawRect(RectF(2f, 2f, w - 2f, h - 2f), focusPaint)
            }
        }

        /**
         * Draws SVG overlay drawables resolved from [BlissRenderAttachment].
         * Each overlay is positioned at ([OverlaySpec.xOffsetPx], [OverlaySpec.yOffsetPx])
         * relative to the top-left of the cell, sized at [OverlaySpec.sizeFraction] * cellW.
         */
        private fun drawOverlays(canvas: Canvas, w: Float, h: Float) {
            overlays.forEach { spec ->
                val d = spec.drawable ?: return@forEach
                val size = (w * spec.sizeFraction).toInt().coerceAtLeast(1)
                val left = (spec.xOffsetPx).toInt().coerceIn(0, (w - size).toInt())
                // yOffsetPx is negative when overlay sits above the base glyph
                val top  = (h / 2f + spec.yOffsetPx - size / 2f)
                    .toInt().coerceIn(0, (h - size).toInt())
                d.setBounds(left, top, left + size, top + size)
                d.draw(canvas)
            }
        }

        private fun drawIndicators(canvas: Canvas, w: Float, h: Float) {
            val dotR  = w * 0.08f
            val baseY = h - dotR * 2.5f

            indicators.forEachIndexed { i, type ->
                val cx = dotR * 2.5f + i * (dotR * 3f)
                when (type) {
                    "plural" -> canvas.drawCircle(cx, baseY, dotR, indicatorPaint)
                    "past"   -> {
                        val paint = Paint(indicatorPaint).apply { strokeWidth = dotR * 0.8f }
                        canvas.drawLine(cx + dotR, baseY, cx - dotR, baseY, paint)
                        canvas.drawLine(cx - dotR, baseY, cx, baseY - dotR, paint)
                        canvas.drawLine(cx - dotR, baseY, cx, baseY + dotR, paint)
                    }
                    "future" -> {
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
