package com.blueapps.egyptianwriter.bliss

import android.content.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [BlissSemanticComposer] — Patch 6 coverage.
 *
 * ## Fix (audit EG, 2026-07-21) — rewritten against the current API
 *
 * This file previously declared a `TestLookup : BlissLookup` stub backed by
 * a fictional `findByLemma`/`findBucket`/`hasSynset` interface and a
 * `BlissEntry` type, asserted against a `CompositionStage` enum and a
 * `ComposedBlissWord.compositionStage`/`component.bciSymbolId` shape, and
 * used the JUnit4 API (`org.junit.Test`) while every other file in this
 * package had already migrated to JUnit5. None of that matches the real,
 * current production types:
 * - [BlissLookup] is a concrete singleton with a *private* constructor
 *   (`getInstance()`) — it was never `open` and could not actually be
 *   subclassed; the interface the stub assumed does not exist on it.
 * - [ComposedBlissWord] exposes `compositionPath: `[CompositionPath]`, not
 *   `compositionStage: CompositionStage` (`CompositionStage` never existed;
 *   `CompositionPath` is also what `Stage`, a `@Deprecated` typealias,
 *   resolves to).
 * - Each component is a [ResolvedBlissComponent] exposing `symbol:
 *   `[BlissSymbol]` (whose id field is `bciAvId`, not `bciId`), not a flat
 *   `bciSymbolId`.
 *
 * This means the file had not actually compiled — and therefore had never
 * run — for some time; none of its assertions were exercising real code.
 *
 * The rewrite below tests the same behaviours (Stage A direct synset hit,
 * Stage B bucket classifier/specifier composition, Stage C off by default,
 * the `compose()` legacy shim) against the real [BlissLookup] singleton,
 * seeded via the shared [injectBlissTables] / [resetBlissLookupSingleton]
 * reflection helpers in `BlissTestUtils.kt` (the same mechanism
 * [BlissLookupTest] uses) instead of a hand-rolled double that can silently
 * drift out of sync with the class it is meant to stand in for again.
 */
@DisplayName("BlissSemanticComposer")
class BlissSemanticComposerTest {

    private val fakeContext: Context = mock<Context>().also { ctx ->
        whenever(ctx.applicationContext).thenReturn(ctx)
        whenever(ctx.packageName).thenReturn("com.blueapps.egyptianwriter.test")
    }

    private lateinit var lookup:   BlissLookup
    private lateinit var composer: BlissSemanticComposer

    @BeforeEach
    fun setUp() {
        resetBlissLookupSingleton()
        lookup = BlissLookup.getInstance(fakeContext)
        // Fixture design:
        // - "house" resolves directly (Stage A): its own synset (HOUSE_SYNSET)
        //   sits outside every WordNet bucket range checked by Stage B
        //   (BUCKET_OTHER), so it can never interfere with the Stage B fixture.
        // - "big_house" resolves to SPECIFIER_ID, whose synset (SPECIFIER_SYNSET)
        //   falls in the NOUN bucket. CLASSIFIER_ID shares that *exact* synset
        //   value and is declared *before* SPECIFIER_ID in the `synsets` map
        //   (Kotlin's mapOf(...) preserves insertion order via LinkedHashMap).
        //   Stage B's classifier search keeps the *first* entry with the
        //   lowest |delta|; since both have delta 0 to the specifier's own
        //   synset, CLASSIFIER_ID — encountered first — wins, and
        //   SPECIFIER_ID's own (later, tied) delta does not overwrite it
        //   (BlissSemanticComposer uses a strict "<" comparison). This
        //   deterministically produces a 2-component [classifier, specifier]
        //   result with distinct lemmas, exactly like two real BCI-AV entries
        //   that happen to share a WordNet synset offset would in production.
        injectBlissTables(
            lookup,
            names = mapOf(
                HOUSE_ID      to "house",
                CLASSIFIER_ID to "container",
                SPECIFIER_ID  to "big_house"
            ),
            synsets = mapOf(
                HOUSE_ID      to HOUSE_SYNSET,
                CLASSIFIER_ID to SPECIFIER_SYNSET,
                SPECIFIER_ID  to SPECIFIER_SYNSET
            ),
            lexicon = mapOf(
                "house"     to HOUSE_ID,
                "big_house" to SPECIFIER_ID
            )
        )
        composer = BlissSemanticComposer(lookup)
    }

    // ── Stage A ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("Stage A — direct synset hit")
    inner class StageA {

        @Test @DisplayName("returns a single-component ComposedBlissWord via SYNONYM_SYNSET")
        fun directSynsetHit() {
            val result = composer.composeStructured("house", "en")

            assertNotNull(result, "Expected non-null ComposedBlissWord for Stage A word")
            assertEquals(CompositionPath.SYNONYM_SYNSET, result!!.compositionPath)
            assertEquals(1, result.components.size)
            assertEquals("house", result.lemma)
            assertEquals("en", result.sourceLang)
        }

        @Test @DisplayName("component carries the correct BCI-AV symbol id")
        fun componentCarriesCorrectId() {
            val result = composer.composeStructured("house", "en")!!
            val component = result.components.first()
            assertEquals(HOUSE_ID, component.symbol.bciAvId)
        }
    }

    // ── Stage B ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("Stage B — hypernym classifier bucket match")
    inner class StageB {

        @Test @DisplayName("returns a two-component ComposedBlissWord via SEMANTIC_DECOMPOSITION")
        fun bucketMatchReturnsTwoComponents() {
            val result = composer.composeStructured("big_house", "en")

            assertNotNull(result, "Expected non-null ComposedBlissWord for Stage B word")
            assertEquals(CompositionPath.SEMANTIC_DECOMPOSITION, result!!.compositionPath)
            assertEquals(2, result.components.size)
        }

        @Test @DisplayName("classifier and specifier components have distinct lemmas")
        fun componentsHaveDistinctLemmas() {
            val result = composer.composeStructured("big_house", "en")!!
            val lemmas = result.components.map { it.lemma }
            assertEquals(2, lemmas.distinct().size, "Expected 2 distinct lemmas")
        }

        @Test @DisplayName("classifier component resolves to CLASSIFIER_ID, not SPECIFIER_ID")
        fun classifierComponentIsDistinctFromSpecifier() {
            val result = composer.composeStructured("big_house", "en")!!
            val ids = result.components.map { it.symbol.bciAvId }
            assertTrue(CLASSIFIER_ID in ids, "Expected classifier BCI id $CLASSIFIER_ID among components")
            assertTrue(SPECIFIER_ID in ids, "Expected specifier BCI id $SPECIFIER_ID among components")
        }
    }

    // ── Stage C ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("Stage C — orthographic fallback")
    inner class StageC {

        @Test @DisplayName("disabled by default: unresolvable word returns null")
        fun disabledByDefaultReturnsNull() {
            // "xyzzy" is absent from both the lexicon and lemma index, and
            // enableOrthographicFallback defaults to false, so Stage C never
            // even runs — Stage A/B simply fail to resolve the token at all.
            val result = composer.composeStructured("xyzzy", "en")
            assertNull(result, "Stage C must not activate when enableOrthographicFallback=false")
        }
    }

    // ── Legacy shim ───────────────────────────────────────────────────────────

    @Nested @DisplayName("Legacy compose() shim")
    inner class LegacyShim {

        @Test @DisplayName("returns a BlissSymbol with MatchType.SEMANTIC for a known word")
        fun returnsSemanticMatchType() {
            val symbol = composer.compose("house", "en")
            assertNotNull(symbol, "compose() shim must not return null for known word")
            assertEquals(MatchType.SEMANTIC, symbol!!.matchType)
        }

        @Test @DisplayName("is consistent with composeStructured().toFlatSymbol()")
        fun consistentWithStructuredToFlatSymbol() {
            val structured   = composer.composeStructured("house", "en")!!
            val fromStructured = structured.toFlatSymbol()
            val fromShim        = composer.compose("house", "en")!!

            assertEquals(fromStructured.bciAvId, fromShim.bciAvId)
            assertEquals(fromStructured.matchType, fromShim.matchType)
        }
    }

    companion object {
        // Stage A fixture
        private const val HOUSE_ID     = 12335
        private const val HOUSE_SYNSET = 1L // outside every WordNet bucket range → BUCKET_OTHER

        // Stage B fixture — see the insertion-order comment in setUp() above.
        private const val CLASSIFIER_ID     = 14001
        private const val SPECIFIER_ID      = 14002
        private const val SPECIFIER_SYNSET  = 100_000_050L // WordNet noun bucket
    }
}

// ── ResolvedBlissComponent.hasOverlay ──────────────────────────────────────────

/**
 * Unit tests for [ResolvedBlissComponent.hasOverlay].
 *
 * ## Fix (audit EG, 2026-07-21)
 * The original `BlissSemanticComposerTest` had a "Indicators are classified
 * as overlay or linear correctly" test that called
 * `composer.composeStructured("house_past", "en")` expecting indicator
 * overlays to appear in the result's `renderAttachments`. That premise does
 * not hold for the real [BlissSemanticComposer]: none of its three stages
 * ever populate [ResolvedBlissComponent.indicators] or
 * [ResolvedBlissComponent.renderAttachments] with anything beyond their
 * empty defaults — indicator attachment happens later in the pipeline
 * ([BlissTranslator.attachIndicators], on the flat [BlissSymbol] list) and
 * SVG overlay resolution happens in `BlissRenderer`, not in the composer.
 * The old test was already written defensively around this
 * (`if (result != null) { … } // if null … also acceptable`), i.e. it could
 * never actually fail either way and exercised nothing.
 *
 * [ResolvedBlissComponent.hasOverlay] itself is real, testable logic
 * (`indicators.isNotEmpty() || renderAttachments.any { it.isOverlay }`) that
 * had zero direct coverage. Testing it directly — by constructing
 * [ResolvedBlissComponent] instances rather than going through the composer
 * — is both simpler and actually exercises the property it's meant to.
 */
@DisplayName("ResolvedBlissComponent.hasOverlay")
class ResolvedBlissComponentHasOverlayTest {

    private val baseSymbol = BlissSymbol(
        bciAvId   = 12335,
        name      = "house",
        matchType = MatchType.SEMANTIC
    )

    @Test @DisplayName("false when indicators and renderAttachments are both empty")
    fun noOverlayByDefault() {
        val component = ResolvedBlissComponent(symbol = baseSymbol)
        assertFalse(component.hasOverlay)
    }

    @Test @DisplayName("true when indicators is non-empty")
    fun overlayFromIndicators() {
        val component = ResolvedBlissComponent(symbol = baseSymbol, indicators = listOf("past"))
        assertTrue(component.hasOverlay)
    }

    @Test @DisplayName("true when a renderAttachment has isOverlay = true, even with empty indicators")
    fun overlayFromRenderAttachment() {
        val attachment = BlissRenderAttachment(indicatorName = "past", isOverlay = true)
        val component  = ResolvedBlissComponent(symbol = baseSymbol, renderAttachments = listOf(attachment))
        assertTrue(component.hasOverlay)
    }

    @Test @DisplayName("false when every renderAttachment is linear (isOverlay = false)")
    fun noOverlayWhenAllAttachmentsAreLinear() {
        val attachment = BlissRenderAttachment(indicatorName = "intensifier", isOverlay = false)
        val component  = ResolvedBlissComponent(symbol = baseSymbol, renderAttachments = listOf(attachment))
        assertFalse(component.hasOverlay)
    }
}
