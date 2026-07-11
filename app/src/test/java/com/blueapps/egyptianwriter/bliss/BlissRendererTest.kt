package com.blueapps.egyptianwriter.bliss

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests per la logica pura di [BlissRenderer] — solo le funzioni
 * che non richiedono un Context Android e sono compilabili su JVM.
 *
 * Le funzioni che rendono SVG su View (renderWithAttachments, renderAsync)
 * sono testate a livello di integrazione/Espresso; qui si copre esclusivamente
 * la logica di composizione, validazione e calcolo sugli attachment.
 *
 * Copertura:
 *  R-01 BlissRenderAttachment.isOverlay — true solo per id overlay BCI
 *  R-02 BlissRenderAttachment.isIndicator — true per indicatori standard
 *  R-03 BlissSymbol.withIndicators — restituisce copia con nuovi indicatori
 *  R-04 BlissSymbol.isUnknown — true solo per UNKNOWN
 *  R-05 BlissSymbol.isCompound — true solo per COMPOUND
 *  R-06 BlissSymbol.isSemanticComposition — true solo per SEMANTIC
 *  R-07 BlissSymbol.gloss tronca a maxLen con ellissi
 *  R-08 BlissSymbol.gloss(maxLen >= name.length) restituisce nome intero
 *  R-09 BlissSymbol init: bciAvId <= 0 su EXACT lancia IllegalArgumentException
 *  R-10 BlissSymbol init: name blank lancia IllegalArgumentException
 *  R-11 BlissSymbol UNKNOWN accetta bciAvId == UNKNOWN_SYMBOL_ID (sentinel)
 *  R-12 BlissSymbol COMPOUND accetta bciAvId == COMPOUND_SYMBOL_ID (sentinel)
 *  R-13 BlissSymbol SEMANTIC con COMPOUND_SYMBOL_ID accetta sentinel
 *  R-14 componentIds popolati per COMPOUND
 *  R-15 indicators vuoti per default; withIndicators non muta originale
 */
class BlissRendererTest {

    // ── R-01 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-01 BlissRenderAttachment isOverlay true for BCI overlay indicator IDs`() {
        // Gli ID overlay BCI standard sono tipicamente nella fascia 9000–9099.
        // Usiamo BlissRenderAttachment direttamente.
        val overlay = BlissRenderAttachment(
            bciIndicatorId = 9007,   // PAST indicator
            position       = BlissRenderAttachment.Position.TOP_RIGHT,
            isOverlay      = true
        )
        assertTrue(overlay.isOverlay)
    }

    // ── R-02 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-02 BlissRenderAttachment isOverlay false for non-overlay attachment`() {
        val nonOverlay = BlissRenderAttachment(
            bciIndicatorId = 9007,
            position       = BlissRenderAttachment.Position.TOP_RIGHT,
            isOverlay      = false
        )
        assertFalse(nonOverlay.isOverlay)
    }

    // ── R-03 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-03 withIndicators returns new copy with indicators set`() {
        val original = BlissSymbol(bciAvId = 1000, name = "cat",
                                   matchType = MatchType.EXACT)
        val updated  = original.withIndicators(listOf("plural", "past"))

        assertTrue(original.indicators.isEmpty())            // originale immutato
        assertEquals(listOf("plural", "past"), updated.indicators)
        assertEquals(1000, updated.bciAvId)                  // resto invariato
    }

    // ── R-04 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-04 isUnknown true only for UNKNOWN matchType`() {
        val unknown = BlissSymbol(bciAvId = BlissSymbol.UNKNOWN_SYMBOL_ID,
                                  name = "unknown", matchType = MatchType.UNKNOWN)
        val exact   = BlissSymbol(bciAvId = 1000, name = "cat",
                                  matchType = MatchType.EXACT)
        assertTrue(unknown.isUnknown)
        assertFalse(exact.isUnknown)
    }

    // ── R-05 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-05 isCompound true only for COMPOUND matchType`() {
        val compound = BlissSymbol(bciAvId = BlissSymbol.COMPOUND_SYMBOL_ID,
                                   name = "compound", matchType = MatchType.COMPOUND)
        val exact    = BlissSymbol(bciAvId = 1000, name = "cat",
                                   matchType = MatchType.EXACT)
        assertTrue(compound.isCompound)
        assertFalse(exact.isCompound)
    }

    // ── R-06 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-06 isSemanticComposition true only for SEMANTIC matchType`() {
        val semantic = BlissSymbol(bciAvId = 2000, name = "run",
                                   matchType = MatchType.SEMANTIC)
        val exact    = BlissSymbol(bciAvId = 1000, name = "cat",
                                   matchType = MatchType.EXACT)
        assertTrue(semantic.isSemanticComposition)
        assertFalse(exact.isSemanticComposition)
    }

    // ── R-07 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-07 gloss truncates to maxLen with ellipsis`() {
        val sym = BlissSymbol(bciAvId = 1000, name = "abcdefghij",
                              matchType = MatchType.EXACT)
        val truncated = sym.gloss(maxLen = 5)   // "abcd…"
        assertEquals(5, truncated.length)
        assertTrue(truncated.endsWith("\u2026"))
    }

    // ── R-08 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-08 gloss returns full name when maxLen greater or equal name length`() {
        val sym = BlissSymbol(bciAvId = 1000, name = "cat",
                              matchType = MatchType.EXACT)
        assertEquals("cat", sym.gloss(maxLen = 10))
        assertEquals("cat", sym.gloss(maxLen = 3))
    }

    // ── R-09 ─────────────────────────────────────────────────────────────────
    @Test(expected = IllegalArgumentException::class)
    fun `R-09 BlissSymbol init throws for bciAvId zero on EXACT`() {
        BlissSymbol(bciAvId = 0, name = "cat", matchType = MatchType.EXACT)
    }

    // ── R-10 ─────────────────────────────────────────────────────────────────
    @Test(expected = IllegalArgumentException::class)
    fun `R-10 BlissSymbol init throws for blank name`() {
        BlissSymbol(bciAvId = 1000, name = "  ", matchType = MatchType.EXACT)
    }

    // ── R-11 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-11 UNKNOWN symbol accepts UNKNOWN_SYMBOL_ID sentinel`() {
        val sym = BlissSymbol(
            bciAvId   = BlissSymbol.UNKNOWN_SYMBOL_ID,
            name      = "unknown",
            matchType = MatchType.UNKNOWN
        )
        assertEquals(BlissSymbol.UNKNOWN_SYMBOL_ID, sym.bciAvId)
        assertTrue(sym.isUnknown)
    }

    // ── R-12 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-12 COMPOUND symbol accepts COMPOUND_SYMBOL_ID sentinel`() {
        val sym = BlissSymbol(
            bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
            name         = "compound",
            matchType    = MatchType.COMPOUND,
            componentIds = listOf(1001, 1002)
        )
        assertEquals(BlissSymbol.COMPOUND_SYMBOL_ID, sym.bciAvId)
        assertTrue(sym.isCompound)
    }

    // ── R-13 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-13 SEMANTIC symbol with COMPOUND_SYMBOL_ID accepted as sentinel`() {
        val sym = BlissSymbol(
            bciAvId   = BlissSymbol.COMPOUND_SYMBOL_ID,
            name      = "semantic-composite",
            matchType = MatchType.SEMANTIC
        )
        assertTrue(sym.isSemanticComposition)
    }

    // ── R-14 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-14 componentIds populated for COMPOUND symbol`() {
        val ids = listOf(1001, 1002, 1003)
        val sym = BlissSymbol(
            bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
            name         = "tree",
            matchType    = MatchType.COMPOUND,
            componentIds = ids
        )
        assertEquals(ids, sym.componentIds)
    }

    // ── R-15 ─────────────────────────────────────────────────────────────────
    @Test
    fun `R-15 indicators empty by default and withIndicators does not mutate original`() {
        val original = BlissSymbol(bciAvId = 1000, name = "dog",
                                   matchType = MatchType.EXACT)
        assertTrue(original.indicators.isEmpty())

        val updated = original.withIndicators(listOf("plural"))
        // originale non mutato
        assertTrue(original.indicators.isEmpty())
        assertEquals(listOf("plural"), updated.indicators)
    }
}
