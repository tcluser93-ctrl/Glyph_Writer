package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * SVG-output tests for [BlissGlyphXBuilder].
 *
 * Tag / attribute names are read from the companion-object constants so the
 * tests stay in sync automatically if the schema evolves:
 *   root tag  = [BlissGlyphXBuilder.TAG_ANCIENT_TEXT]  ("ancientText")
 *   sign code = [BlissGlyphXBuilder.ATTR_CODE]         ("code")
 *   sign match= [BlissGlyphXBuilder.ATTR_MATCH]        ("match")
 *   sign word = [BlissGlyphXBuilder.ATTR_WORD]         ("word")
 *
 * [MatchType] alias is declared in BlissTestUtils.kt (package-internal).
 */
@DisplayName("BlissGlyphXBuilder — SVG output")
class BlissGlyphXBuilderSvgTest {

    private val xpath = XPathFactory.newInstance().newXPath()

    private fun sym(
        id: Int,
        name: String,
        matchType: MatchType = MatchType.EXACT,
        sourceWord: String = name
    ) = BlissSymbol(
        bciAvId    = id,
        name       = name,
        matchType  = matchType,
        sourceWord = sourceWord
    )

    private fun xpathStr(doc: Document, expr: String): String =
        xpath.evaluate(expr, doc)

    private fun xpathNum(doc: Document, expr: String): Int =
        (xpath.evaluate(expr, doc, XPathConstants.NUMBER) as Double).toInt()

    @Nested
    @DisplayName("Root SVG element")
    inner class RootSvg {

        @Test
        @DisplayName("build() returns a non-null Document")
        fun returnsDocument() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            assertNotNull(builder.build(emptyList()))
        }

        @Test
        @DisplayName("Root element tag is '${BlissGlyphXBuilder.TAG_ANCIENT_TEXT}'")
        fun rootTagIsAncientText() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build(emptyList())
            assertEquals(BlissGlyphXBuilder.TAG_ANCIENT_TEXT, doc.documentElement.tagName)
        }
    }

    @Nested
    @DisplayName("Symbol encoding")
    inner class SymbolEncoding {

        @Test
        @DisplayName("EXACT match → match attr = 'EXACT'")
        fun exactMatch() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build(listOf(sym(1, "a", MatchType.EXACT)))
            val match = xpathStr(doc, "//sign/@match")
            assertEquals("EXACT", match)
        }

        @Test
        @DisplayName("LEMMA match → match attr = 'LEMMA'")
        fun lemmaMatch() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build(listOf(sym(1, "a", MatchType.LEMMA)))
            assertEquals("LEMMA", xpathStr(doc, "//sign/@match"))
        }

        @Test
        @DisplayName("NGRAM match → match attr = 'NGRAM'")
        fun ngramMatch() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build(listOf(sym(1, "a", MatchType.NGRAM)))
            assertEquals("NGRAM", xpathStr(doc, "//sign/@match"))
        }

        @Test
        @DisplayName("UNKNOWN match → match attr = 'UNKNOWN'")
        fun unknownMatch() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build(listOf(sym(1, "a", MatchType.UNKNOWN)))
            assertEquals("UNKNOWN", xpathStr(doc, "//sign/@match"))
        }

        @Test
        @DisplayName("FALLBACK_CATEGORY match → match attr = 'FALLBACK_CATEGORY'")
        fun fallbackCategoryMatch() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build(listOf(sym(1, "a", MatchType.FALLBACK_CATEGORY)))
            assertEquals("FALLBACK_CATEGORY", xpathStr(doc, "//sign/@match"))
        }

        @Test
        @DisplayName("code attr = 'B' + bciAvId")
        fun codeAttribute() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build(listOf(sym(12335, "walk")))
            assertEquals("B12335", xpathStr(doc, "//sign/@code"))
        }

        @Test
        @DisplayName("sourceWord is stored in 'word' attribute")
        fun sourceWordAttribute() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build(listOf(sym(1, "run", sourceWord = "running")))
            // The builder emits setAttribute(ATTR_WORD, sym.sourceWord)
            // ATTR_WORD = "word"
            assertEquals("running", xpathStr(doc, "//sign/@${BlissGlyphXBuilder.ATTR_WORD}"))
        }
    }

    @Nested
    @DisplayName("Line wrapping")
    inner class LineWrapping {

        @Test
        @DisplayName("4 symbols, perLine=4 → 1 line")
        fun exactlyOneLine() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build((1..4).map { sym(it, "s$it") })
            assertEquals(1, xpathNum(doc, "count(//line)"))
        }

        @Test
        @DisplayName("5 symbols, perLine=4 → 2 lines")
        fun overflow() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build((1..5).map { sym(it, "s$it") })
            assertEquals(2, xpathNum(doc, "count(//line)"))
        }

        @Test
        @DisplayName("8 symbols, perLine=4 → 2 lines")
        fun twoFullLines() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build((1..8).map { sym(it, "s$it") })
            assertEquals(2, xpathNum(doc, "count(//line)"))
        }

        @Test
        @DisplayName("9 symbols, perLine=4 → 3 lines")
        fun threeLines() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc = builder.build((1..9).map { sym(it, "s$it") })
            assertEquals(3, xpathNum(doc, "count(//line)"))
        }
    }

    @Nested
    @DisplayName("Indicators")
    inner class Indicators {

        @Test
        @DisplayName("withIndicators([\"plural\"]) emits one <indicator type=\"plural\"/>")
        fun singleIndicator() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val symbol = sym(12335, "walk").withIndicators(listOf("plural"))
            val doc = builder.build(listOf(symbol))
            assertEquals(1, xpathNum(doc, "count(//indicator)"))
            assertEquals("plural", xpathStr(doc, "//indicator/@type"))
        }

        @Test
        @DisplayName("withIndicators([\"past\", \"negative\"]) emits two indicators")
        fun multipleIndicators() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val symbol = sym(1, "go").withIndicators(listOf("past", "negative"))
            val doc = builder.build(listOf(symbol))
            assertEquals(2, xpathNum(doc, "count(//indicator)"))
        }

        @Test
        @DisplayName("withIndicators(emptyList()) emits no indicators")
        fun noIndicators() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val symbol = sym(1, "go").withIndicators(emptyList())
            val doc = builder.build(listOf(symbol))
            assertEquals(0, xpathNum(doc, "count(//indicator)"))
        }
    }
}
