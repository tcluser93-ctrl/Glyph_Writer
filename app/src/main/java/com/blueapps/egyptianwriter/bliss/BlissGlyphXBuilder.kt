package com.blueapps.egyptianwriter.bliss

import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

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
 * These are emitted as `<indicator type=\"plural|past|future\"/>` children of
 * `<group>` so the rendering layer can draw overlays.
 * Indicators are attached to symbols that carry a non-null [BlissSymbol.indicators]
 * list (extension point; defaults to empty — backward-compatible).
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

        /** F1-09: allocated once, reused across all build() calls. */
        private val DOC_FACTORY: DocumentBuilderFactory =
            DocumentBuilderFactory.newInstance()

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

    // ── public API ────────────────────────────────────────────────────────────

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
     * Stateful tail reference for incremental [append] calls.
     * Re-create when starting a new document.
     */
    class TailRef {
        var line:  Element? = null
        var count: Int      = 0
    }

    // ── private helpers ───────────────────────────────────────────────────────

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
