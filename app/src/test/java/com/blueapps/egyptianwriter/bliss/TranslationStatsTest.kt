package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Focused tests for [TranslationStats] and its [TranslationStats.from] factory.
 *
 * These tests are extracted from [BlissViewModelTest] into their own file so
 * the CI test report shows a clear per-class breakdown.
 *
 * All tests are pure JVM — no Android context, no coroutines, no mocks.
 */
@DisplayName("TranslationStats")
class TranslationStatsTest {

    private fun sym(mt: BlissSymbol.MatchType) = BlissSymbol(
        bciAvId    = 1,
        name       = "x",
        matchType  = mt,
        sourceWord = "x",
        gloss      = "x"
    )

    @Nested
    @DisplayName("from() factory")
    inner class FromFactory {

        @Test
        @DisplayName("Empty list → total=0, all counters=0")
        fun emptyList() {
            val s = TranslationStats.from(emptyList())
            assertEquals(0, s.total)
            assertEquals(0, s.exact)
            assertEquals(0, s.lemma)
            assertEquals(0, s.ngram)
            assertEquals(0, s.unknown)
        }

        @Test
        @DisplayName("All EXACT → exact==total, others zero")
        fun allExact() {
            val s = TranslationStats.from(List(4) { sym(BlissSymbol.MatchType.EXACT) })
            assertEquals(4, s.total)
            assertEquals(4, s.exact)
            assertEquals(0, s.lemma + s.ngram + s.unknown)
        }

        @Test
        @DisplayName("1 of each type → all counters == 1")
        fun oneOfEach() {
            val s = TranslationStats.from(
                BlissSymbol.MatchType.values().map { sym(it) }
            )
            // EXACT, LEMMA, NGRAM, FALLBACK_CATEGORY, UNKNOWN — total=5
            assertEquals(BlissSymbol.MatchType.values().size, s.total)
            assertEquals(1, s.exact)
            assertEquals(1, s.lemma)
            assertEquals(1, s.ngram)
            assertEquals(1, s.unknown)
        }

        @Test
        @DisplayName("FALLBACK_CATEGORY symbols are NOT counted as exact/lemma/ngram/unknown")
        fun fallbackCategoryNotCounted() {
            // FALLBACK_CATEGORY does not map to any named bucket in TranslationStats;
            // it contributes to total but NOT to the 4 named counters.
            val s = TranslationStats.from(
                List(2) { sym(BlissSymbol.MatchType.FALLBACK_CATEGORY) }
            )
            assertEquals(2, s.total)
            assertEquals(0, s.exact + s.lemma + s.ngram + s.unknown,
                "FALLBACK_CATEGORY should not increment any named counter")
        }
    }

    @Nested
    @DisplayName("coverage property")
    inner class Coverage {

        @Test
        @DisplayName("Empty list → coverage = 0.0")
        fun emptyList() = assertEquals(0f, TranslationStats.from(emptyList()).coverage)

        @Test
        @DisplayName("All EXACT → coverage = 1.0")
        fun allExact() = assertEquals(1.0f,
            TranslationStats.from(List(3) { sym(BlissSymbol.MatchType.EXACT) }).coverage,
            0.001f)

        @Test
        @DisplayName("All UNKNOWN → coverage = 0.0")
        fun allUnknown() = assertEquals(0.0f,
            TranslationStats.from(List(3) { sym(BlissSymbol.MatchType.UNKNOWN) }).coverage,
            0.001f)

        @Test
        @DisplayName("50% unknown → coverage = 0.5")
        fun half() {
            val s = TranslationStats.from(
                List(3) { sym(BlissSymbol.MatchType.EXACT) } +
                List(3) { sym(BlissSymbol.MatchType.UNKNOWN) }
            )
            assertEquals(0.5f, s.coverage, 0.001f)
        }
    }
}
