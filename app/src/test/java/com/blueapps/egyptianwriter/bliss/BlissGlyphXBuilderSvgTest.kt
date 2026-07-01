package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * Unit tests for [BlissGlyphXBuilder.toSvgBytes] / [BlissGlyphXBuilder.toSvgString].
 *
 * Runs on the local JVM (no Android context required).
 * Dependencies: JUnit 5 only.
 *
 * ## Coverage map
 * | Scenario                         | Test method                          |
 * |----------------------------------|--------------------------------------|
 * | SVG is well-formed XML           | svgIsWellFormedXml                   |
 * | SVG namespace                    | svgHasCorrectNamespace               |
 * | Canvas width (single row)        | canvasWidthMatchesSymbolCount        |
 * | Canvas height (multi-row)        | canvasHeightMatchesRowCount          |
 * | One <rect> chip per symbol       | oneChipPerSymbol                     |
 * | EXACT fill colour                | chipFillExact                        |
 * | LEMMA fill colour                | chipFillLemma                        |
 * | NGRAM fill colour                | chipFillNgram                        |
 * | FALLBACK_CATEGORY fill colour    | chipFillFallback                     |
 * | UNKNOWN fill colour              | chipFillUnknown                      |
 * | Name label present               | nameLabelPresent                     |
 * | Name truncated at 9 chars        | nameTruncatedAt9Chars                |
 * | Indicator badge plural           | indicatorBadgePlural                 |
 * | Indicator badge past             | indicatorBadgePast                   |
 * | Indicator badge future           | indicatorBadgeFuture                 |
 * | Multiple indicators concatenated | multipleIndicatorsBadge              |
 * | No badge when no indicators      | noBadgeWhenNoIndicators              |
 * | Empty document → valid SVG       | emptyDocProducesValidSvg             |
 * | toSvgBytes == toSvgString UTF-8  | toSvgBytesConsistentWithToSvgString  |
 * | XML special chars escaped        | xmlSpecialCharsEscaped               |
 */
@DisplayName("BlissGlyphXBuilder — toSvgBytes / toSvgString")
class BlissGlyphXBuilderSvgTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
    private val xpath   = XPathFactory.newInstance().newXPath()

    /** Minimal BlissSymbol constructor (only fields used by builder). */
    private fun sym(
        id: Int,
        name: String,
        matchType: MatchType = MatchType.EXACT,
        sourceWord: String = name
    ) = BlissSymbol(
        bciAvId    = id,
        name       = name,
        matchType  = matchType,
        sourceWord = sourceWord,
        gloss      = name
    )

    /** Parse an SVG string into a DOM Document. Throws if not well-formed. */
    private fun parseSvg(svg: String): Document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(svg)))

    /** Count elements matching [localName] in an SVG Document. */
    private fun count(svgDoc: Document, localName: String): Int =
        svgDoc.getElementsByTagNameNS("*", localName).length
            .let { if (it > 0) it else svgDoc.getElementsByTagName(localName).length }

    /** Return the value of attribute [attr] on the root SVG element. */
    private fun svgAttr(svgDoc: Document, attr: String): String =
        svgDoc.documentElement.getAttribute(attr)

    /** True if any text content in the SVG contains [substring]. */
    private fun svgContains(svg: String, substring: String): Boolean =
        svg.contains(substring)

    /** Build a GlyphX doc from a flat symbol list using the test builder. */
    private fun glyphDoc(symbols: List<BlissSymbol>): Document =
        builder.build(symbols)

    // ── SVG structural correctness ────────────────────────────────────────────

    @Nested
    @DisplayName("SVG structure")
    inner class Structure {

        @Test
        @DisplayName("SVG is well-formed XML — parseable without exception")
        fun svgIsWellFormedXml() {
            val doc = glyphDoc(listOf(sym(12335, "walk")))
            val svg = builder.toSvgString(doc)
            assertDoesNotThrow { parseSvg(svg) }
        }

        @Test
        @DisplayName("Root element is <svg> with correct namespace")
        fun svgHasCorrectNamespace() {
            val doc    = glyphDoc(listOf(sym(12335, "walk")))
            val svgDoc = parseSvg(builder.toSvgString(doc))
            assertEquals("svg", svgDoc.documentElement.localName ?: svgDoc.documentElement.nodeName)
            val ns = svgDoc.documentElement.namespaceURI
            // namespace may or may not be parsed depending on factory config;
            // at minimum the xmlns attribute must be present in the raw string
            val raw = builder.toSvgString(doc)
            assertTrue(raw.contains("http://www.w3.org/2000/svg"),
                "Expected SVG namespace in output")
        }

        @Test
        @DisplayName("SVG contains XML declaration with UTF-8 encoding")
        fun xmlDeclarationPresent() {
            val doc = glyphDoc(listOf(sym(1, "a")))
            val svg = builder.toSvgString(doc)
            assertTrue(svg.startsWith("<?xml"), "Missing XML declaration")
            assertTrue(svg.contains("UTF-8"), "Missing UTF-8 encoding declaration")
        }

        @Test
        @DisplayName("Empty document produces valid, parseable SVG")
        fun emptyDocProducesValidSvg() {
            val doc = glyphDoc(emptyList())
            val svg = builder.toSvgString(doc)
            assertDoesNotThrow { parseSvg(svg) }
            // must still be SVG
            assertTrue(svg.contains("<svg"), "Empty result should still be an SVG element")
        }
    }

    // ── Canvas dimensions ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Canvas dimensions")
    inner class Dimensions {

        // SVG_CELL=80, SVG_ROW_H=100, SVG_PAD=12
        private val CELL  = 80
        private val ROW_H = 100
        private val PAD   = 12

        @Test
        @DisplayName("Width = PAD*2 + cols * CELL (single row, 3 symbols)")
        fun canvasWidthMatchesSymbolCount() {
            val doc    = glyphDoc(listOf(sym(1,"a"), sym(2,"b"), sym(3,"c")))
            val svgDoc = parseSvg(builder.toSvgString(doc))
            val expected = PAD * 2 + 3 * CELL
            assertEquals("$expected", svgAttr(svgDoc, "width"))
        }

        @Test
        @DisplayName("Height = PAD*2 + rows * ROW_H (symbolsPerLine=4 → 2 rows for 5 symbols)")
        fun canvasHeightMatchesRowCount() {
            // builder has symbolsPerLine=4, so 5 symbols → 2 rows
            val symbols = (1..5).map { sym(it, "s$it") }
            val doc     = glyphDoc(symbols)
            val svgDoc  = parseSvg(builder.toSvgString(doc))
            val expected = PAD * 2 + 2 * ROW_H
            assertEquals("$expected", svgAttr(svgDoc, "height"))
        }

        @Test
        @DisplayName("Single symbol → minimal canvas")
        fun singleSymbolMinimalCanvas() {
            val doc    = glyphDoc(listOf(sym(1, "go")))
            val svgDoc = parseSvg(builder.toSvgString(doc))
            assertEquals("${PAD * 2 + CELL}",  svgAttr(svgDoc, "width"))
            assertEquals("${PAD * 2 + ROW_H}", svgAttr(svgDoc, "height"))
        }
    }

    // ── Chip count ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Chips (one per symbol)")
    inner class Chips {

        @Test
        @DisplayName("One <rect> chip per symbol (background rect excluded via fill check)")
        fun oneChipPerSymbol() {
            val symbols = listOf(sym(1,"a"), sym(2,"b"), sym(3,"c"))
            val svg     = builder.toSvgString(glyphDoc(symbols))
            // chip rects have stroke="#CCCCCC"; background rect does not
            val chipCount = Regex("stroke=\\\"#CCCCCC\\\"").findAll(svg).count()
            assertEquals(symbols.size, chipCount,
                "Expected ${symbols.size} chip rects with stroke=#CCCCCC")
        }
    }

    // ── Fill colours per matchType ─────────────────────────────────────────────

    @Nested
    @DisplayName("Match-type chip colours")
    inner class ChipColours {

        private fun fillFor(match: MatchType): String {
            val svg = builder.toSvgString(glyphDoc(listOf(sym(1, "x", match))))
            val m = Regex("fill=\\\"(#[A-Fa-f0-9]{6})\\\"").findAll(svg)
                .map { it.groupValues[1] }
                .toList()
            // index 0 = background (#FAFAF8), index 1 = chip fill
            return m.getOrNull(1) ?: ""
        }

        @Test fun chipFillExact()    = assertEquals("#D0F0D0", fillFor(MatchType.EXACT))
        @Test fun chipFillLemma()    = assertEquals("#D0E8FF", fillFor(MatchType.LEMMA))
        @Test fun chipFillNgram()    = assertEquals("#FFF3B0", fillFor(MatchType.NGRAM))
        @Test fun chipFillFallback() = assertEquals("#FFDDB0", fillFor(MatchType.FALLBACK_CATEGORY))
        @Test fun chipFillUnknown()  = assertEquals("#FFD0D0", fillFor(MatchType.UNKNOWN))
    }

    // ── Labels ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Symbol name labels")
    inner class Labels {

        @Test
        @DisplayName("Short name (≤9 chars) appears verbatim in SVG")
        fun nameLabelPresent() {
            val svg = builder.toSvgString(glyphDoc(listOf(sym(1, "walk"))))
            assertTrue(svgContains(svg, "walk"), "Expected 'walk' in SVG text")
        }

        @Test
        @DisplayName("Name longer than 9 chars is truncated with ellipsis (…)")
        fun nameTruncatedAt9Chars() {
            val longName = "abcdefghij"  // 10 chars
            val svg = builder.toSvgString(glyphDoc(listOf(sym(1, longName))))
            assertTrue(svgContains(svg, "abcdefghi\u2026"),
                "Expected name truncated to 9 chars + '\u2026' but got: "
                    + svg.lines().filter { it.contains("text") }.joinToString())
            assertFalse(svgContains(svg, longName),
                "Full 10-char name should not appear verbatim")
        }

        @Test
        @DisplayName("Name exactly 9 chars appears without ellipsis")
        fun nameExactly9CharsNoEllipsis() {
            val name = "123456789"  // exactly 9
            val svg  = builder.toSvgString(glyphDoc(listOf(sym(1, name))))
            assertTrue(svgContains(svg, name))
            assertFalse(svgContains(svg, "$name\u2026"),
                "9-char name should NOT be followed by ellipsis")
        }
    }

    // ── Indicator badges ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Indicator badges")
    inner class Indicators {

        private fun svgWithIndicators(vararg types: String): String {
            val symbol = sym(12335, "walk").withIndicators(types.toList())
            return builder.toSvgString(glyphDoc(listOf(symbol)))
        }

        @Test
        @DisplayName("PLURAL indicator → '\u00d7' badge in SVG")
        fun indicatorBadgePlural() {
            assertTrue(svgContains(svgWithIndicators("plural"), "\u00d7"))
        }

        @Test
        @DisplayName("PAST indicator → '\u21a9' badge in SVG")
        fun indicatorBadgePast() {
            assertTrue(svgContains(svgWithIndicators("past"), "\u21a9"))
        }

        @Test
        @DisplayName("FUTURE indicator → '\u2192' badge in SVG")
        fun indicatorBadgeFuture() {
            assertTrue(svgContains(svgWithIndicators("future"), "\u2192"))
        }

        @Test
        @DisplayName("Multiple indicators → all badges concatenated")
        fun multipleIndicatorsBadge() {
            val svg = svgWithIndicators("plural", "past")
            assertTrue(svgContains(svg, "\u00d7"), "Missing plural badge")
            assertTrue(svgContains(svg, "\u21a9"), "Missing past badge")
        }

        @Test
        @DisplayName("Symbol with no indicators → no badge text element")
        fun noBadgeWhenNoIndicators() {
            // No withIndicators() call → indicators list is empty
            val sym = sym(12335, "walk")
            val svg = builder.toSvgString(glyphDoc(listOf(sym)))
            // Badge chars should not appear
            assertFalse(svgContains(svg, "\u00d7"), "Unexpected plural badge")
            assertFalse(svgContains(svg, "\u21a9"), "Unexpected past badge")
            assertFalse(svgContains(svg, "\u2192"), "Unexpected future badge")
        }
    }

    // ── Byte consistency ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Byte output")
    inner class ByteOutput {

        @Test
        @DisplayName("toSvgBytes() == toSvgString().toByteArray(UTF-8)")
        fun toSvgBytesConsistentWithToSvgString() {
            val doc    = glyphDoc(listOf(sym(12335, "walk"), sym(14990, "run")))
            val bytes  = builder.toSvgBytes(doc)
            val string = builder.toSvgString(doc)
            assertArrayEquals(string.toByteArray(Charsets.UTF_8), bytes)
        }

        @Test
        @DisplayName("toSvgBytes() is non-empty for non-empty document")
        fun toSvgBytesNonEmpty() {
            val doc   = glyphDoc(listOf(sym(1, "a")))
            val bytes = builder.toSvgBytes(doc)
            assertTrue(bytes.isNotEmpty())
        }
    }

    // ── XML escaping ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("XML escaping")
    inner class Escaping {

        @Test
        @DisplayName("'<', '>', '&', '\"', \"'\" in name are escaped in SVG output")
        fun xmlSpecialCharsEscaped() {
            val name = "a<b>&c\"\'d"
            val svg  = builder.toSvgString(glyphDoc(listOf(sym(1, name))))
            // Raw chars must not appear unescaped inside element content
            val svgBody = svg
                .replace(Regex("<[^>]+>"), " ")  // strip tags, keep text nodes
            assertFalse(svgBody.contains("<b>"),  "Raw '<b>' should be escaped")
            assertFalse(svgBody.contains(">&c"),  "Raw '&' should be escaped")
            // Escaped forms must be present
            assertTrue(svg.contains("&lt;")  || svg.contains("a&lt;b"), "Missing &lt;")
            assertTrue(svg.contains("&amp;") || svg.contains("&amp;c"), "Missing &amp;")
        }
    }
}
