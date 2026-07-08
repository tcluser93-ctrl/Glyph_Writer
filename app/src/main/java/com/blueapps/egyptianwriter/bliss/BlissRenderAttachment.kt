package com.blueapps.egyptianwriter.bliss

/**
 * Render-level metadata for a single Bliss indicator or modifier.
 *
 * ## Patch 6 — Purpose
 * In the BCI/Bliss rendering model, indicators are **combining characters**:
 * they are drawn *on top of* the base symbol glyph (overlay), not next to it.
 * [BlissRenderAttachment] carries the information that [BlissRenderer] needs
 * to position and draw each indicator as an SVG overlay in real-time.
 *
 * Separating render instructions from the linguistic model ([ResolvedBlissComponent])
 * keeps the composition layer clean: the composer decides *what* indicators are
 * needed; the renderer decides *how* to paint them.
 *
 * ## Attachment types
 * - **Overlay** (`isOverlay = true`): drawn above or on the base glyph (BCI
 *   combining indicator behaviour).  [xOffsetPx] and [yOffsetPx] are offsets
 *   relative to the base glyph's top-left corner.
 * - **Linear** (`isOverlay = false`): drawn as a separate adjacent symbol in
 *   the symbol strip.  [xOffsetPx] is treated as a gap in px; [yOffsetPx] unused.
 *
 * @param indicatorName  Canonical Bliss indicator name (e.g. "past", "plural",
 *                       "future", "evaluation", "intensifier").  Must be
 *                       resolvable by [BlissRenderer.resolveIndicatorId].
 * @param bciIndicatorId BCI-AV ID of the indicator symbol.  Set to -1 if the ID
 *                       has not yet been resolved; [BlissRenderer] will resolve
 *                       it on demand.
 * @param isOverlay      `true` for BCI combining (above-glyph) indicators;
 *                       `false` for linear modifiers placed adjacent to the base.
 * @param xOffsetPx      Horizontal offset in pixels from the base glyph anchor.
 *                       Ignored for linear attachments.
 * @param yOffsetPx      Vertical offset in pixels from the base glyph anchor.
 *                       Positive = downward (Android coordinate system).
 *                       For overlay indicators, typically negative (above baseline).
 * @param priority       Render order when multiple overlays are stacked.
 *                       Lower values are drawn first (below others).
 */
data class BlissRenderAttachment(
    val indicatorName:  String,
    val bciIndicatorId: Int    = UNRESOLVED_ID,
    val isOverlay:      Boolean = true,
    val xOffsetPx:      Float  = 0f,
    val yOffsetPx:      Float  = DEFAULT_OVERLAY_Y_OFFSET_PX,
    val priority:       Int    = 0
) {
    /** True when the BCI indicator ID has been resolved. */
    val isResolved: Boolean get() = bciIndicatorId > 0

    /** Returns a copy with the given [resolvedId] filled in. */
    fun withResolvedId(resolvedId: Int): BlissRenderAttachment =
        copy(bciIndicatorId = resolvedId)

    companion object {
        /** Sentinel: BCI indicator ID not yet resolved. */
        const val UNRESOLVED_ID = -1

        /**
         * Default vertical offset for overlay indicators (above base glyph).
         * Expressed in device-independent pixels; [BlissRenderer] must scale
         * this value by the current density.
         */
        const val DEFAULT_OVERLAY_Y_OFFSET_PX = -14f
    }
}
