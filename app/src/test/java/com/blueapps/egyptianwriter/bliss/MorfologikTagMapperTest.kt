package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for [MorfologikTagMapper.toBlissIndicators].
 *
 * [MorfologikTagMapper] is a pure `object` with no Android dependencies,
 * so these tests run on the JVM without Robolectric or mocks.
 *
 * ## Coverage
 * - Null / blank input → empty list
 * - Plural detection: `plural`, `+pl`, `:pl`, `:p` (with false-positive guards)
 * - Past detection: `past`, `pst`, `imperf`, `ppast`, participio `pp`
 * - Future detection: `fut`, `future`
 * - Mixed tags: multiple indicators from a single raw tag
 * - Unknown / unrecognised tags: no indicators emitted
 * - Case-insensitivity: upper-case raw tags handled correctly
 */
@DisplayName("MorfologikTagMapper — toBlissIndicators")
class MorfologikTagMapperTest {

    // ── Null / blank ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Null and blank input")
    inner class NullBlank {

        @Test
        @DisplayName("null tag → empty list")
        fun nullTagReturnsEmpty() {
            assertTrue(MorfologikTagMapper.toBlissIndicators(null).isEmpty())
        }

        @Test
        @DisplayName("empty string tag → empty list")
        fun emptyTagReturnsEmpty() {
            assertTrue(MorfologikTagMapper.toBlissIndicators("").isEmpty())
        }

        @Test
        @DisplayName("blank/whitespace tag → empty list")
        fun blankTagReturnsEmpty() {
            assertTrue(MorfologikTagMapper.toBlissIndicators("   ").isEmpty())
        }
    }

    // ── Plural ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Plural detection")
    inner class Plural {

        @Test
        @DisplayName("'plural' keyword → INDICATOR_PLURAL")
        fun pluralKeyword() {
            val result = MorfologikTagMapper.toBlissIndicators("noun:plural")
            assertTrue(BlissTranslator.INDICATOR_PLURAL in result)
        }

        @Test
        @DisplayName("'+pl' suffix → INDICATOR_PLURAL")
        fun plusPl() {
            val result = MorfologikTagMapper.toBlissIndicators("Noun+pl")
            assertTrue(BlissTranslator.INDICATOR_PLURAL in result)
        }

        @Test
        @DisplayName("':pl' → INDICATOR_PLURAL")
        fun colonPl() {
            val result = MorfologikTagMapper.toBlissIndicators("NOUN:PL")
            assertTrue(BlissTranslator.INDICATOR_PLURAL in result)
        }

        @Test
        @DisplayName("':p' without ':past' or ':pres' → INDICATOR_PLURAL")
        fun colonPWithoutPast() {
            val result = MorfologikTagMapper.toBlissIndicators("noun:p")
            assertTrue(BlissTranslator.INDICATOR_PLURAL in result)
        }

        @Test
        @DisplayName("':past' must NOT trigger plural")
        fun colonPastDoesNotTriggerPlural() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:past")
            assertFalse(BlissTranslator.INDICATOR_PLURAL in result)
        }

        @Test
        @DisplayName("':pres' must NOT trigger plural")
        fun colonPresDoesNotTriggerPlural() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:pres")
            assertFalse(BlissTranslator.INDICATOR_PLURAL in result)
        }
    }

    // ── Past ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Past detection")
    inner class Past {

        @Test
        @DisplayName("'past' → INDICATOR_PAST")
        fun pastKeyword() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:past")
            assertTrue(BlissTranslator.INDICATOR_PAST in result)
        }

        @Test
        @DisplayName("'pst' abbreviation → INDICATOR_PAST")
        fun pstAbbreviation() {
            val result = MorfologikTagMapper.toBlissIndicators("V:pst")
            assertTrue(BlissTranslator.INDICATOR_PAST in result)
        }

        @Test
        @DisplayName("'imperf' → INDICATOR_PAST")
        fun imperfective() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:imperf")
            assertTrue(BlissTranslator.INDICATOR_PAST in result)
        }

        @Test
        @DisplayName("'ppast' → INDICATOR_PAST")
        fun ppast() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:ppast")
            assertTrue(BlissTranslator.INDICATOR_PAST in result)
        }

        @Test
        @DisplayName("'part:pp' → INDICATOR_PAST")
        fun participioPP() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:part:pp")
            assertTrue(BlissTranslator.INDICATOR_PAST in result)
        }
    }

    // ── Future ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Future detection")
    inner class Future {

        @Test
        @DisplayName("'fut' abbreviation → INDICATOR_FUTURE")
        fun futAbbreviation() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:fut")
            assertTrue(BlissTranslator.INDICATOR_FUTURE in result)
        }

        @Test
        @DisplayName("'future' keyword → INDICATOR_FUTURE")
        fun futureKeyword() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:future")
            assertTrue(BlissTranslator.INDICATOR_FUTURE in result)
        }

        @Test
        @DisplayName("'FUT' upper-case → INDICATOR_FUTURE (case-insensitive)")
        fun futUpperCase() {
            val result = MorfologikTagMapper.toBlissIndicators("VERB:FUT")
            assertTrue(BlissTranslator.INDICATOR_FUTURE in result)
        }
    }

    // ── Mixed and unknown ───────────────────────────────────────────────

    @Nested
    @DisplayName("Mixed tags and unknown")
    inner class MixedAndUnknown {

        @Test
        @DisplayName("tag with both plural and past emits both indicators")
        fun pluralAndPast() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:past:plural")
            assertTrue(BlissTranslator.INDICATOR_PLURAL in result)
            assertTrue(BlissTranslator.INDICATOR_PAST   in result)
            assertFalse(BlissTranslator.INDICATOR_FUTURE in result)
        }

        @Test
        @DisplayName("tag with all three tenses emits all three indicators")
        fun allThree() {
            val result = MorfologikTagMapper.toBlissIndicators("verb:plural:past:fut")
            assertEquals(3, result.size)
            assertTrue(BlissTranslator.INDICATOR_PLURAL in result)
            assertTrue(BlissTranslator.INDICATOR_PAST   in result)
            assertTrue(BlissTranslator.INDICATOR_FUTURE in result)
        }

        @Test
        @DisplayName("unrecognised tag 'adj:comp' → empty list")
        fun unknownTagReturnsEmpty() {
            assertTrue(MorfologikTagMapper.toBlissIndicators("adj:comp").isEmpty())
        }

        @Test
        @DisplayName("no duplicate indicators for repeated signals in one tag")
        fun noDuplicates() {
            val result = MorfologikTagMapper.toBlissIndicators("plural:plural:+pl")
            assertEquals(1, result.count { it == BlissTranslator.INDICATOR_PLURAL })
        }
    }
}
