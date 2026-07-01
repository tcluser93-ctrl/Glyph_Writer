package com.blueapps.egyptianwriter.bliss

import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.ByteArrayOutputStream
import java.io.StringWriter

/**
 * Converts a list of [BlissSymbol]s into a GlyphX DOM [Document] that
 * [com.blueapps.thoth.ThothView] can render via `setGlyphXText()`.
 *
 * ## Enterprise changes (F1-09 / F1-10 / F2-10)
 *
 * ### F1-09 — Singleton DocumentBuilderFactory
 * `DocumentBuilderFactory.newInstance()` was called inside `build()` on every
 * invocation — it allocates a factory via ServiceLoader (~5ms per call on
 * mid-range devices). Moved to a companion `val`, allocated once.
 *
 * ### F1-10 — O(n) append with tail pointer
 * The original `append()` called `getElementsByTagName(TAG_LINE)` to find the last
 * line, then `getElementsByTagName(TAG_GROUP)` inside it — both are O(depth) tree
 * traversals, making the loop O(n²) for a long document.
 * Fixed by keeping a direct `Element?` reference to the last line and a counter,
 * eliminating all `getElementsByTagName()` calls inside the hot loop.
 *
 * ### F2-10 — Grammatical indicator elements
 * BCI standard requires three indicator types rendered above/below a symbol:
 *   - PLURAL   (dot above) — BCI indicator code "plural"
 *   - PAST     (line below, past tense)
 *   - FUTURE   (line above, future tense)
 * These are emitted as `<indicator type="plural|past|future"/>` children of
 * `<group>` so the rendering layer can draw overlays.
 * Indicators are attached to symbols that carry a non-null [BlissSymbol.indicators]
 * list (extension point; defaults to empty — backward-compatible).
 *
 * ### F3-01 — toSvgBytes / toSvgString
 * Serialises a GlyphX DOM to a standalone SVG image (UTF-8, vector).
 * Uses only `javax.xml.transform` (Android stdlib — no extra dependency).
 * See [toSvgBytes] for full documentation.
 *
 * ## GlyphX schema (extended)
 * ```xml
 * <ancientText>
 *   <line>
 *     <group>
 *       <sign code="B12335" name="walk" match="EXACT" word="camminare" />
 *       <!-- optional: -->
 *       <indicator type="plural" />
 *     </group>
 *   </line>
 * </ancientText>
 * ```
 */
class BlissGlyphXBuilder(
    private val symbolsPerLine: Int = AUTO_SYMBOLS_PER_LINE
) {

    companion object {
        /** Use this sentinel to trigger automatic line-breaking based on [screenWidthPx]. */
        const val AUTO_SYMBOLS_PER_LINE = -1

        const val TAG_ANCIENT_TEXT = "ancientText"
        const val TAG_LINE         = "line"
        const val TAG_GROUP        = "group"
        const val TAG_SIGN         = "sign"
        const val TAG_INDICATOR    = "indicator"
        const val ATTR_CODE        = "code"
        const val ATTR_NAME        = "name"
        const val ATTR_MATCH       = "match"
        const val ATTR_WORD        = "word"
        const val ATTR_TYPE        = "type"
        const val BLISS_PREFIX     = "B"

        // ── SVG layout constants ─────────────────────────────────────────
        /** Width of each symbol cell in SVG user units (pixels at 1:1). */
        private const val SVG_CELL   = 80
        /** Height of each symbol row (cell + label space). */
        private const val SVG_ROW_H  = 100
        /** Padding around the whole SVG canvas. */
        private const val SVG_PAD    = 12
        /** Font size for the symbol name label. */
        private const val SVG_FONT   = 10
        /** Font size for the BCI-AV ID badge. */
        private const val SVG_FONT_ID = 8
        /** Max chars for the symbol name before truncation. */
        private const val SVG_MAX_LABEL = 9
        /** Corner radius of the symbol chip rect. */
        private const val SVG_RADIUS = 6

        /** F1-09: allocated once, reused across all build() calls. */
        private val DOC_FACTORY: DocumentBuilderFactory =
            DocumentBuilderFactory.newInstance()

        /** F3-01: javax.xml.transform factory, allocated once. */
        private val TRANSFORMER_FACTORY: TransformerFactory =
            TransformerFactory.newInstance()

        // ── Match-type chip colours (same as BlissTranslateFragment.chipColor) ─
        private val MATCH_FILL = mapOf(
            "EXACT"             to "#D0F0D0",
            "LEMMA"             to "#D0E8FF",
            "NGRAM"             to "#FFF3B0",
            "FALLBACK_CATEGORY" to "#FFDDB0",
            "UNKNOWN"           to "#FFD0D0"
        )
        private const val MATCH_FILL_DEFAULT = "#E8E8E8"

        // ── Indicator badge characters ─────────────────────────────────
        private val INDICATOR_BADGE = mapOf(
            BlissTranslator.INDICATOR_PLURAL to "×",
            BlissTranslator.INDICATOR_PAST   to "↩",
            BlissTranslator.INDICATOR_FUTURE to "→"
        )

        /** Extract numeric BCI-AV ID from a sign code attribute. Returns -1 if not Bliss. */
        fun parseBciAvId(code: String): Int =
            if (code.startsWith(BLISS_PREFIX))
                code.removePrefix(BLISS_PREFIX).toIntOrNull() ?: -1
            else -1

        /**
         * Compute symbols per line from screen width and cell size.
         * Use this to initialise [BlissGlyphXBuilder] at runtime.
         *
         * @param screenWidthPx  Usable horizontal space in pixels.
         * @param cellSizePx     Width of a single symbol cell in pixels.
         * @param minPerLine     Lower bound (default 4) — avoids single-symbol lines on tiny screens.
         * @param maxPerLine     Upper bound (default 16) — avoids very wide lines on tablets.
         */
        fun computeSymbolsPerLine(
            screenWidthPx: Int,
            cellSizePx: Int,
            minPerLine: Int = 4,
            maxPerLine: Int = 16
        ): Int = ((screenWidthPx / cellSizePx.coerceAtLeast(1))
            .coerceIn(minPerLine, maxPerLine))
    }

    // ══ public API ═════════════════════════════════════════════════

    /**
     * Build a fresh GlyphX [Document] from [symbols].
     *
     * @param symbols        Symbols to render.
     * @param screenWidthPx  Used only when [symbolsPerLine] == [AUTO_SYMBOLS_PER_LINE].
     * @param cellSizePx     Cell size for auto line-breaking.
     */
    fun build(
        symbols: List<BlissSymbol>,
        screenWidthPx: Int = 1080,
        cellSizePx: Int    = 200
    ): Document {
        val perLine = resolvedPerLine(screenWidthPx, cellSizePx)
        val doc  = newDocument()
        val root = doc.createElement(TAG_ANCIENT_TEXT)
        doc.appendChild(root)

        if (symbols.isEmpty()) {
            root.appendChild(doc.createElement(TAG_LINE))
            return doc
        }

        // F1-10: direct tail pointer — no getElementsByTagName in hot loop
        var lineEl = doc.createElement(TAG_LINE).also { root.appendChild(it) }
        var countInLine = 0

        for (sym in symbols) {
            if (countInLine == perLine) {
                lineEl = doc.createElement(TAG_LINE).also { root.appendChild(it) }
                countInLine = 0
            }
            lineEl.appendChild(newGroup(doc, sym))
            countInLine++
        }

        return doc
    }

    /**
     * Merge [extra] symbols into [existingDoc].
     * Appends to the last line's tail — O(n) instead of O(n²) (F1-10).
     *
     * @param tailRef  Mutable holder of the last [Element] in the document.
     *                 Create once with [TailRef] and reuse across append() calls.
     */
    fun append(
        existingDoc: Document,
        extra: List<BlissSymbol>,
        tailRef: TailRef,
        screenWidthPx: Int = 1080,
        cellSizePx: Int    = 200
    ): Document {
        if (extra.isEmpty()) return existingDoc
        val perLine = resolvedPerLine(screenWidthPx, cellSizePx)

        val root = existingDoc.documentElement
            ?: return build(extra, screenWidthPx, cellSizePx)

        if (tailRef.line == null) {
            tailRef.line  = existingDoc.createElement(TAG_LINE).also { root.appendChild(it) }
            tailRef.count = 0
        }

        for (sym in extra) {
            if (tailRef.count >= perLine) {
                tailRef.line  = existingDoc.createElement(TAG_LINE).also { root.appendChild(it) }
                tailRef.count = 0
            }
            tailRef.line!!.appendChild(newGroup(existingDoc, sym))
            tailRef.count++
        }
        return existingDoc
    }

    /**
     * F3-01 — Serialise a GlyphX [Document] to a standalone SVG [ByteArray].
     *
     * The SVG is generated by walking the GlyphX DOM tree (ancientText → line →
     * group → sign/indicator) and emitting one coloured chip per `<sign>` element.
     * Chip colours match [BlissTranslateFragment.chipColor] for visual consistency.
     *
     * ## Layout
     * ```
     * ┌────────────────────────────────────────────────────────────────────────────────────└
     * |  [chip: name, BCI id, match colour]  [chip]  ...  |
     * |  [indicator badge if any]                         |
     * └────────────────────────────────────────────────────────────────────────────────────┘
     * ```
     *
     * ## Guarantees
     * - Returns a non-empty, well-formed SVG even for an empty document (empty canvas).
     * - Safe to call on any thread (pure computation, no Android context needed).
     * - No external dependencies beyond `javax.xml.transform` (Android stdlib).
     *
     * @param doc  A GlyphX [Document] produced by [build] or [append].
     * @return     UTF-8 encoded SVG bytes ready to write to file or send via Intent.
     */
    fun toSvgBytes(doc: Document): ByteArray =
        toSvgString(doc).toByteArray(Charsets.UTF_8)

    /**
     * F3-01 variant — returns the SVG as a [String] (useful for testing / logging).
     * [toSvgBytes] delegates here.
     */
    fun toSvgString(doc: Document): String {
        // ── 1. Collect rows from GlyphX DOM ────────────────────────────────────
        data class SvgSign(
            val name:       String,
            val bciCode:    String,
            val matchType:  String,
            val indicators: List<String>
        )
        data class SvgRow(val signs: List<SvgSign>)

        val rows = mutableListOf<SvgRow>()
        val root = doc.documentElement ?: return emptySvg()

        val lineNodes = root.getElementsByTagName(TAG_LINE)
        for (li in 0 until lineNodes.length) {
            val lineEl = lineNodes.item(li) as? Element ?: continue
            val signs  = mutableListOf<SvgSign>()

            val groupNodes = lineEl.getElementsByTagName(TAG_GROUP)
            for (gi in 0 until groupNodes.length) {
                val groupEl = groupNodes.item(gi) as? Element ?: continue

                // first <sign> child
                val signNodes = groupEl.getElementsByTagName(TAG_SIGN)
                val signEl    = signNodes.item(0) as? Element ?: continue
                val name      = signEl.getAttribute(ATTR_NAME).ifBlank { "?" }
                val code      = signEl.getAttribute(ATTR_CODE).ifBlank { "" }
                val match     = signEl.getAttribute(ATTR_MATCH).ifBlank { "UNKNOWN" }

                // <indicator> siblings
                val indNodes = groupEl.getElementsByTagName(TAG_INDICATOR)
                val indicators = (0 until indNodes.length).mapNotNull {
                    (indNodes.item(it) as? Element)?.getAttribute(ATTR_TYPE)?.ifBlank { null }
                }

                signs += SvgSign(name, code, match, indicators)
            }
            if (signs.isNotEmpty()) rows += SvgRow(signs)
        }

        if (rows.isEmpty()) return emptySvg()

        // ── 2. Compute canvas size ──────────────────────────────────────────
        val maxCols  = rows.maxOf { it.signs.size }
        val numRows  = rows.size
        val svgW     = SVG_PAD * 2 + maxCols * SVG_CELL
        val svgH     = SVG_PAD * 2 + numRows * SVG_ROW_H

        // ── 3. Build SVG markup ────────────────────────────────────────────
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
        sb.append("width=\"$svgW\" height=\"$svgH\" ")
        sb.append("viewBox=\"0 0 $svgW $svgH\">\n")

        // background
        sb.append("  <rect width=\"$svgW\" height=\"$svgH\" fill=\"#FAFAF8\" rx=\"4\"/>\n")

        // title (accessibility)
        sb.append("  <title>Bliss Translation</title>\n")

        rows.forEachIndexed { rowIdx, row ->
            val rowY = SVG_PAD + rowIdx * SVG_ROW_H

            row.signs.forEachIndexed { colIdx, sign ->
                val cellX = SVG_PAD + colIdx * SVG_CELL
                val chipX = cellX + 2
                val chipY = rowY + 2
                val chipW = SVG_CELL - 4
                val chipH = SVG_ROW_H - 22  // leave space for label below

                val fill = MATCH_FILL[sign.matchType] ?: MATCH_FILL_DEFAULT

                // chip background
                sb.append("  <rect x=\"$chipX\" y=\"$chipY\" ")
                sb.append("width=\"$chipW\" height=\"$chipH\" ")
                sb.append("fill=\"$fill\" rx=\"$SVG_RADIUS\" stroke=\"#CCCCCC\" stroke-width=\"1\"/>\n")

                // BCI-AV ID (top-right corner, small)
                val bciId = sign.bciCode.removePrefix(BLISS_PREFIX)
                if (bciId.isNotEmpty()) {
                    val idX = chipX + chipW - 2
                    val idY = chipY + SVG_FONT_ID + 1
                    sb.append("  <text x=\"$idX\" y=\"$idY\" ")
                    sb.append("font-size=\"$SVG_FONT_ID\" fill=\"#888888\" ")
                    sb.append("text-anchor=\"end\" font-family=\"sans-serif\">")
                    sb.append(bciId.escapeXml())
                    sb.append("</text>\n")
                }

                // symbol name label (centred horizontally, inside chip)
                val labelTrunc = sign.name.take(SVG_MAX_LABEL)
                    .let { if (sign.name.length > SVG_MAX_LABEL) "$it…" else it }
                val labelX = chipX + chipW / 2
                val labelY = chipY + chipH / 2 + SVG_FONT / 2  // vertically centred
                sb.append("  <text x=\"$labelX\" y=\"$labelY\" ")
                sb.append("font-size=\"$SVG_FONT\" fill=\"#222222\" ")
                sb.append("text-anchor=\"middle\" font-family=\"sans-serif\" ")
                sb.append("font-weight=\"600\">")
                sb.append(labelTrunc.escapeXml())
                sb.append("</text>\n")

                // source word label (below chip)
                val wordY = rowY + SVG_ROW_H - 6
                sb.append("  <text x=\"$labelX\" y=\"$wordY\" ")
                sb.append("font-size=\"$SVG_FONT\" fill=\"#555555\" ")
                sb.append("text-anchor=\"middle\" font-family=\"sans-serif\">")
                // bciCode is e.g. "B12335"; no source-word in SVG DOM — use name as fallback
                sb.append(sign.name.take(SVG_MAX_LABEL).escapeXml())
                sb.append("</text>\n")

                // indicator badges (top-left of chip)
                if (sign.indicators.isNotEmpty()) {
                    val badge = sign.indicators
                        .mapNotNull { INDICATOR_BADGE[it] }
                        .joinToString("")
                    if (badge.isNotEmpty()) {
                        val badgeX = chipX + 3
                        val badgeY = chipY + SVG_FONT + 1
                        sb.append("  <text x=\"$badgeX\" y=\"$badgeY\" ")
                        sb.append("font-size=\"$SVG_FONT\" fill=\"#444444\" ")
                        sb.append("font-family=\"sans-serif\">")
                        sb.append(badge.escapeXml())
                        sb.append("</text>\n")
                    }
                }
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * Stateful tail reference for incremental [append] calls.
     * Re-create when starting a new document.
     */
    class TailRef {
        var line:  Element? = null
        var count: Int      = 0
    }

    // ══ private helpers ═══════════════════════════════════════════════

    private fun resolvedPerLine(screenWidthPx: Int, cellSizePx: Int): Int =
        if (symbolsPerLine == AUTO_SYMBOLS_PER_LINE)
            computeSymbolsPerLine(screenWidthPx, cellSizePx)
        else symbolsPerLine

    private fun newDocument(): Document =
        DOC_FACTORY.newDocumentBuilder().newDocument()

    /**
     * Builds:
     * ```xml
     * <group>
     *   <sign code="B{id}" name="{name}" match="{type}" word="{sourceWord}" />
     *   [<indicator type="plural|past|future" />]   <!-- if indicators present -->
     * </group>
     * ```
     */
    private fun newGroup(doc: Document, sym: BlissSymbol): Element {
        val group = doc.createElement(TAG_GROUP)

        val sign = doc.createElement(TAG_SIGN).apply {
            setAttribute(ATTR_CODE,  "$BLISS_PREFIX${sym.bciAvId}")
            setAttribute(ATTR_NAME,  sym.name)
            setAttribute(ATTR_MATCH, sym.matchType.name)
            setAttribute(ATTR_WORD,  sym.sourceWord)
        }
        group.appendChild(sign)

        // F2-10: emit <indicator> elements for grammatical markers
        sym.indicators.forEach { indicator ->
            group.appendChild(
                doc.createElement(TAG_INDICATOR).apply {
                    setAttribute(ATTR_TYPE, indicator)
                }
            )
        }

        return group
    }

    /** Returns a minimal valid empty SVG canvas. */
    private fun emptySvg(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50" viewBox="0 0 100 50">
  <rect width="100" height="50" fill="#FAFAF8" rx="4"/>
  <title>Bliss Translation (empty)</title>
</svg>"""

    /** Escapes XML special characters in text content. */
    private fun String.escapeXml(): String = this
        .replace("&",  "&amp;")
        .replace("<",  "&lt;")
        .replace(">",  "&gt;")
        .replace("\"", "&quot;")
        .replace("'",  "&apos;")
}

/**
 * Extension point: grammatical indicators attached to a [BlissSymbol].
 * The core data class stays clean; indicators are attached via this extension
 * on a mutable companion map so no database schema change is required.
 *
 * Usage:
 *   val sym = BlissSymbol(12335, "walk").withIndicators(listOf("plural"))
 */
fun BlissSymbol.withIndicators(list: List<String>): BlissSymbol {
    BlissSymbolIndicators.map[this] = list
    return this
}

val BlissSymbol.indicators: List<String>
    get() = BlissSymbolIndicators.map[this] ?: emptyList()

/** Weak-keyed storage so indicator state does not prevent GC of BlissSymbol. */
internal object BlissSymbolIndicators {
    val map = java.util.WeakHashMap<BlissSymbol, List<String>>()
}
