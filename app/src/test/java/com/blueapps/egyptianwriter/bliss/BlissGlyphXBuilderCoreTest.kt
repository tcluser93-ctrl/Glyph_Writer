package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Unit tests for [BlissGlyphXBuilder.build] and [BlissGlyphXBuilder.append].
 *
 * These tests cover the GlyphX DOM construction logic (pre-existing).
 * Runs on the local JVM — no Android context required.
 */
@DisplayName("BlissGlyphXBuilder — build() and append()")
class BlissGlyphXBuilderCoreTest {

    private fun sym(
        id: Int,
        name: String,
        matchType: MatchType = MatchType.EXACT
    ) = BlissSymbol(
        bciAvId    = id,
        name       = name,
        matchType  = matchType,
        sourceWord = name,
        gloss      = name
    )

    @Nested
    @DisplayName("build()")
    inner class Build {

        @Test
        @DisplayName("Empty list → single empty <line>")
        fun emptyListProducesSingleLine() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc     = builder.build(emptyList())
            val lines   = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_LINE)
            assertEquals(1, lines.length)
            assertEquals(0, (lines.item(0) as Element).childNodes.length)
        }

        @Test
        @DisplayName("3 symbols, perLine=4 → single line with 3 groups")
        fun threeSymbolsOneLine() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc     = builder.build(listOf(sym(1,"a"), sym(2,"b"), sym(3,"c")))
            val lines   = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_LINE)
            assertEquals(1, lines.length)
            val groups  = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_GROUP)
            assertEquals(3, groups.length)
        }

        @Test
        @DisplayName("5 symbols, perLine=4 → 2 lines (4 + 1)")
        fun fiveSymbolsTwoLines() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc     = builder.build((1..5).map { sym(it, "s$it") })
            val lines   = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_LINE)
            assertEquals(2, lines.length)
        }

        @Test
        @DisplayName("<sign> code attribute is 'B' + bciAvId")
        fun signCodeHasBlissPrefix() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc     = builder.build(listOf(sym(12335, "walk")))
            val sign    = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_SIGN).item(0) as Element
            assertEquals("B12335", sign.getAttribute(BlissGlyphXBuilder.ATTR_CODE))
        }

        @Test
        @DisplayName("<sign> match attribute reflects MatchType")
        fun signMatchAttribute() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc     = builder.build(listOf(sym(1, "run", MatchType.LEMMA)))
            val sign    = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_SIGN).item(0) as Element
            assertEquals("LEMMA", sign.getAttribute(BlissGlyphXBuilder.ATTR_MATCH))
        }

        @Test
        @DisplayName("Indicator emitted as <indicator type=\"plural\"/>")
        fun indicatorElement() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 4)
            val symbol  = sym(12335, "walk").withIndicators(listOf("plural"))
            val doc     = builder.build(listOf(symbol))
            val inds    = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_INDICATOR)
            assertEquals(1, inds.length)
            assertEquals("plural", (inds.item(0) as Element).getAttribute(BlissGlyphXBuilder.ATTR_TYPE))
        }
    }

    @Nested
    @DisplayName("append()")
    inner class Append {

        @Test
        @DisplayName("append() adds groups to existing doc without restarting")
        fun appendAddsGroups() {
            val builder  = BlissGlyphXBuilder(symbolsPerLine = 4)
            val doc      = builder.build(listOf(sym(1, "a"), sym(2, "b")))
            val tailRef  = BlissGlyphXBuilder.TailRef()
            builder.append(doc, listOf(sym(3, "c")), tailRef)
            val groups = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_GROUP)
            assertEquals(3, groups.length)
        }

        @Test
        @DisplayName("append() triggers new line when perLine exceeded")
        fun appendTriggersNewLine() {
            val builder = BlissGlyphXBuilder(symbolsPerLine = 2)
            val doc     = builder.build(listOf(sym(1, "a"), sym(2, "b")))  // 1 full line
            val tailRef = BlissGlyphXBuilder.TailRef()
            builder.append(doc, listOf(sym(3, "c")), tailRef)             // overflows → new line
            val lines = doc.getElementsByTagName(BlissGlyphXBuilder.TAG_LINE)
            assertEquals(2, lines.length)
        }
    }

    @Nested
    @DisplayName("parseBciAvId")
    inner class ParseBciAvId {

        @Test fun validCode()   = assertEquals(12335, BlissGlyphXBuilder.parseBciAvId("B12335"))
        @Test fun noPrefix()   = assertEquals(-1,    BlissGlyphXBuilder.parseBciAvId("12335"))
        @Test fun emptyString() = assertEquals(-1,   BlissGlyphXBuilder.parseBciAvId(""))
        @Test fun textOnly()   = assertEquals(-1,    BlissGlyphXBuilder.parseBciAvId("Bwalk"))
    }
}
