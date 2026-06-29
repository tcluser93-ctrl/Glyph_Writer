package com.blueapps.egyptianwriter.bliss

import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Converts a list of [BlissSymbol]s into a GlyphX DOM [Document] that
 * [com.blueapps.thoth.ThothView] can render directly via `setGlyphXText()`.
 *
 * ## GlyphX schema (reverse-engineered from FileMaster + ThothView)
 *
 * ```xml
 * <ancientText>
 *   <line>
 *     <group>
 *       <sign code="B12335" />
 *     </group>
 *     <group>
 *       <sign code="B17729" />   <!-- unknown -->
 *     </group>
 *   </line>
 * </ancientText>
 * ```
 *
 * Bliss BCI-AV IDs are prefixed with **"B"** to distinguish them from
 * Egyptian Gardiner sign codes (e.g. "A1", "G17") already used by ThothView.
 * The rendering module needs to handle the "B" prefix to load the correct
 * Bliss SVG asset instead of a hieroglyph glyph.
 *
 * Every [BlissSymbol] becomes one `<group>` element with a single `<sign>`
 * child.  N-gram matches are still emitted as a single group (one concept).
 *
 * @param symbolsPerLine  How many symbol groups to place on each `<line>`
 *                        before wrapping.  Default 8 matches ThothView default
 *                        column width for a typical phone screen.
 */
class BlissGlyphXBuilder(
    private val symbolsPerLine: Int = 8
) {

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Build a fresh GlyphX [Document] from [symbols].
     * Always returns a valid document; empty list → document with one empty line.
     */
    fun build(symbols: List<BlissSymbol>): Document {
        val doc  = newDocument()
        val root = doc.createElement(TAG_ANCIENT_TEXT)
        doc.appendChild(root)

        if (symbols.isEmpty()) {
            root.appendChild(newLine(doc))
            return doc
        }

        var lineEl: Element = newLine(doc)
        root.appendChild(lineEl)
        var countInLine = 0

        for (sym in symbols) {
            if (countInLine == symbolsPerLine) {
                lineEl = newLine(doc)
                root.appendChild(lineEl)
                countInLine = 0
            }
            lineEl.appendChild(newGroup(doc, sym))
            countInLine++
        }

        return doc
    }

    /**
     * Merge [extra] symbols into an existing GlyphX [Document] by appending
     * new groups to the last line (or new lines if needed).
     * Useful for incremental "append" mode.
     */
    fun append(existingDoc: Document, extra: List<BlissSymbol>): Document {
        if (extra.isEmpty()) return existingDoc

        val root = existingDoc.documentElement ?: return build(extra)
        val lines = root.getElementsByTagName(TAG_LINE)

        // find last line and its current group count
        val lastLine: Element = if (lines.length > 0)
            lines.item(lines.length - 1) as Element
        else {
            val l = newLine(existingDoc)
            root.appendChild(l)
            l
        }
        var countInLine = lastLine.getElementsByTagName(TAG_GROUP).length

        var currentLine = lastLine
        for (sym in extra) {
            if (countInLine == symbolsPerLine) {
                currentLine = newLine(existingDoc)
                root.appendChild(currentLine)
                countInLine = 0
            }
            currentLine.appendChild(newGroup(existingDoc, sym))
            countInLine++
        }
        return existingDoc
    }

    // ── XML helpers ──────────────────────────────────────────────────────────

    private fun newDocument(): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

    private fun newLine(doc: Document): Element =
        doc.createElement(TAG_LINE)

    /**
     * A `<group>` wrapping a single `<sign code="B{id}" name="{name}" match="{type}" />`.
     *
     * Extra attributes (`name`, `match`) are metadata for debugging / future
     * tooltip rendering and are safely ignored by the current ThothView.
     */
    private fun newGroup(doc: Document, sym: BlissSymbol): Element {
        val group = doc.createElement(TAG_GROUP)
        val sign  = doc.createElement(TAG_SIGN)
        sign.setAttribute(ATTR_CODE,  "$BLISS_PREFIX${sym.bciAvId}")
        sign.setAttribute(ATTR_NAME,  sym.name)
        sign.setAttribute(ATTR_MATCH, sym.matchType.name)
        sign.setAttribute(ATTR_WORD,  sym.sourceWord)
        group.appendChild(sign)
        return group
    }

    // ── constants ────────────────────────────────────────────────────────────

    companion object {
        const val TAG_ANCIENT_TEXT = "ancientText"
        const val TAG_LINE         = "line"
        const val TAG_GROUP        = "group"
        const val TAG_SIGN         = "sign"
        const val ATTR_CODE        = "code"
        const val ATTR_NAME        = "name"
        const val ATTR_MATCH       = "match"
        const val ATTR_WORD        = "word"

        /**
         * Prefix used on all Bliss sign codes to distinguish them from
         * Egyptian Gardiner codes inside a ThothView document.
         * ThothView's sign renderer must handle this prefix.
         */
        const val BLISS_PREFIX     = "B"

        /** Extract numeric BCI-AV ID from a sign code attribute. Returns -1 if not a Bliss code. */
        fun parseBciAvId(code: String): Int =
            if (code.startsWith(BLISS_PREFIX))
                code.removePrefix(BLISS_PREFIX).toIntOrNull() ?: -1
            else -1
    }
}
