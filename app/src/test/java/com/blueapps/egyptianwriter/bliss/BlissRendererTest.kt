package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * BlissRendererTest — Patch 16
 *
 * 15 test JVM puri su [BlissSymbol] e [BlissRenderAttachment].
 * Zero mock, zero dipendenze Android SDK.
 *
 * Esecuzione:
 *   ./gradlew :app:test --tests "com.blueapps.egyptianwriter.bliss.BlissRendererTest"
 */
class BlissRendererTest {

    // ── fixture base ──────────────────────────────────────────────────────────
    private val baseSymbol = BlissSymbol(
        bciAvId      = 12001,
        name         = "house",
        sourceWord   = "house",
        matchType    = BlissSymbol.MatchType.EXACT,
        indicators   = emptyList(),
        componentIds = emptyList()
    )

    private val baseAttachment = BlissRenderAttachment(
        indicatorName  = "action indicator",
        bciIndicatorId = 99001,
        isOverlay      = false
    )

    // ── R-01 ─────────────────────────────────────────────────────────────────
    /** isOverlay == true per attachment BCI overlay. */
    @Test fun r01_attachment_isOverlay_true() {
        val overlay = baseAttachment.copy(isOverlay = true)
        assertTrue(overlay.isOverlay, "Expected isOverlay == true")
    }

    // ── R-02 ─────────────────────────────────────────────────────────────────
    /** isOverlay == false per attachment non-overlay. */
    @Test fun r02_attachment_isOverlay_false() {
        assertFalse(baseAttachment.isOverlay, "Expected isOverlay == false")
    }

    // ── R-03 ─────────────────────────────────────────────────────────────────
    /** withIndicators restituisce copia con nuovi indicatori; originale immutato. */
    @Test fun r03_withIndicators_copiesAndKeepsOriginalImmutable() {
        val newIndicators = listOf(BlissIndicator.PLURAL, BlissIndicator.PAST)
        val copy = baseSymbol.withIndicators(newIndicators)
        assertEquals(newIndicators, copy.indicators)
        assertTrue(baseSymbol.indicators.isEmpty(), "Original must remain with empty indicators")
        assertNotSame(baseSymbol, copy, "withIndicators must return a different instance")
    }

    // ── R-04 ─────────────────────────────────────────────────────────────────
    /** isUnknown true solo per MatchType.UNKNOWN. */
    @Test fun r04_isUnknown_trueOnlyForUnknown() {
        val unknown = baseSymbol.copy(
            bciAvId   = BlissSymbol.UNKNOWN_SYMBOL_ID,
            matchType = BlissSymbol.MatchType.UNKNOWN
        )
        assertTrue(unknown.isUnknown, "isUnknown must be true for UNKNOWN")
        assertFalse(baseSymbol.isUnknown, "isUnknown must be false for EXACT")
    }

    // ── R-05 ─────────────────────────────────────────────────────────────────
    /** isCompound true solo per MatchType.COMPOUND. */
    @Test fun r05_isCompound_trueOnlyForCompound() {
        val compound = baseSymbol.copy(
            bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
            matchType    = BlissSymbol.MatchType.COMPOUND,
            componentIds = listOf(12001, 12002)
        )
        assertTrue(compound.isCompound, "isCompound must be true for COMPOUND")
        assertFalse(baseSymbol.isCompound, "isCompound must be false for EXACT")
    }

    // ── R-06 ─────────────────────────────────────────────────────────────────
    /** isSemanticComposition true solo per MatchType.SEMANTIC. */
    @Test fun r06_isSemanticComposition_trueOnlyForSemantic() {
        val semantic = baseSymbol.copy(
            bciAvId   = BlissSymbol.COMPOUND_SYMBOL_ID,
            matchType = BlissSymbol.MatchType.SEMANTIC
        )
        assertTrue(semantic.isSemanticComposition, "isSemanticComposition must be true for SEMANTIC")
        assertFalse(baseSymbol.isSemanticComposition, "isSemanticComposition must be false for EXACT")
    }

    // ── R-07 ─────────────────────────────────────────────────────────────────
    /** gloss(maxLen) tronca con ellissi Unicode (…). */
    @Test fun r07_gloss_truncatesWithEllipsis() {
        val longName = "a".repeat(50)
        val sym = baseSymbol.copy(name = longName)
        val maxLen = 10
        val g = sym.gloss(maxLen)
        assertTrue(g.endsWith("…"), "Truncated gloss must end with ellipsis")
        assertTrue(g.length <= maxLen + 1, "Truncated gloss must not exceed maxLen chars")
    }

    // ── R-08 ─────────────────────────────────────────────────────────────────
    /** gloss(maxLen >= name.length) restituisce nome intero senza ellissi. */
    @Test fun r08_gloss_noTruncationWhenFits() {
        val sym = baseSymbol.copy(name = "cat")
        val g = sym.gloss(100)
        assertEquals("cat", g)
        assertFalse(g.contains("…"), "No ellipsis expected when name fits")
    }

    // ── R-09 ─────────────────────────────────────────────────────────────────
    /** init lancia IllegalArgumentException per bciAvId == 0 su EXACT. */
    @Test
    fun r09_init_throwsForZeroBciId() {
        assertThrows(IllegalArgumentException::class.java) {
            BlissSymbol(
                bciAvId      = 0,
                name         = "bad",
                sourceWord   = "bad",
                matchType    = BlissSymbol.MatchType.EXACT,
                indicators   = emptyList(),
                componentIds = emptyList()
            )
        }
    }

    // ── R-10 ─────────────────────────────────────────────────────────────────
    /** init lancia IllegalArgumentException per name blank. */
    @Test
    fun r10_init_throwsForBlankName() {
        assertThrows(IllegalArgumentException::class.java) {
            BlissSymbol(
                bciAvId      = 12001,
                name         = "   ",
                sourceWord   = "?",
                matchType    = BlissSymbol.MatchType.EXACT,
                indicators   = emptyList(),
                componentIds = emptyList()
            )
        }
    }

    // ── R-11 ─────────────────────────────────────────────────────────────────
    /** UNKNOWN accetta UNKNOWN_SYMBOL_ID sentinel senza eccezioni. */
    @Test fun r11_unknown_acceptsSentinelId() {
        val sym = BlissSymbol(
            bciAvId      = BlissSymbol.UNKNOWN_SYMBOL_ID,
            name         = "???",
            sourceWord   = "xyzzy",
            matchType    = BlissSymbol.MatchType.UNKNOWN,
            indicators   = emptyList(),
            componentIds = emptyList()
        )
        assertEquals(BlissSymbol.UNKNOWN_SYMBOL_ID, sym.bciAvId)
    }

    // ── R-12 ─────────────────────────────────────────────────────────────────
    /** COMPOUND accetta COMPOUND_SYMBOL_ID sentinel. */
    @Test fun r12_compound_acceptsSentinelId() {
        val sym = BlissSymbol(
            bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
            name         = "ice+cream",
            sourceWord   = "ice cream",
            matchType    = BlissSymbol.MatchType.COMPOUND,
            indicators   = emptyList(),
            componentIds = listOf(12001, 12002)
        )
        assertEquals(BlissSymbol.COMPOUND_SYMBOL_ID, sym.bciAvId)
    }

    // ── R-13 ─────────────────────────────────────────────────────────────────
    /** SEMANTIC con COMPOUND_SYMBOL_ID accettato come sentinel. */
    @Test fun r13_semantic_acceptsCompoundSentinelId() {
        val sym = BlissSymbol(
            bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
            name         = "happiness",
            sourceWord   = "happiness",
            matchType    = BlissSymbol.MatchType.SEMANTIC,
            indicators   = emptyList(),
            componentIds = emptyList()
        )
        assertTrue(sym.isSemanticComposition)
    }

    // ── R-14 ─────────────────────────────────────────────────────────────────
    /** componentIds popolati correttamente per COMPOUND. */
    @Test fun r14_compound_componentIdsPopulated() {
        val ids = listOf(10001, 10002, 10003)
        val sym = baseSymbol.copy(
            bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
            matchType    = BlissSymbol.MatchType.COMPOUND,
            componentIds = ids
        )
        assertEquals(ids, sym.componentIds)
        assertEquals(3, sym.componentIds.size)
    }

    // ── R-15 ─────────────────────────────────────────────────────────────────
    /** indicators vuoti di default; withIndicators non muta originale. */
    @Test fun r15_indicators_defaultEmpty_withIndicatorsImmutable() {
        assertTrue(baseSymbol.indicators.isEmpty(), "Default indicators must be empty")
        val modified = baseSymbol.withIndicators(listOf(BlissIndicator.FUTURE))
        assertTrue(baseSymbol.indicators.isEmpty(), "Original indicators must remain empty")
        assertEquals(1, modified.indicators.size)
        assertEquals(BlissIndicator.FUTURE, modified.indicators[0])
    }
}
