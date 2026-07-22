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
 * Unit tests for [BlissSemanticComposer].
 *
 * ## Fix (audit EG, 2026-07-21) — rewritten against the current API
 *
 * This file previously declared a `TestLookup : BlissLookup` stub backed by
 * a fictional `findByLemma`/`findBucket`/`hasSynset` interface and a
 * `BlissEntry` type, asserted against a `CompositionStage` enum and a
 * `ComposedBlissWord.compositionStage`/`component.bciSymbolId` shape, and
 * used the JUnit4 API (`org.junit.Test`) while every other file in this
 * package had already migrated to JUnit5. None of that matches the real,
 * current production types — see git history for the full story.
 *
 * ## Update (audit EG, 2026-07-22) — Stage A redesign
 * Stage A no longer re-queries [BlissLookup] (see [BlissSemanticComposer]'s
 * KDoc for why the original Patch 5 version of that was permanently
 * unreachable); it now queries a separate [WordNetIndex] for a synonym or
 * nearby hypernym that has a Bliss symbol. The Stage A fixture below was
 * rewritten accordingly, seeded via the shared [injectWordNetTables]
 * reflection helper (the [WordNetIndex] analogue of [injectBlissTables]).
 * Stage B and Stage C are untouched by this pass (Stage B's redesign is
 * tracked separately — see `Report_EG_Tier3g_Opzioni_A_D.md`, Fase 2) so
 * their fixtures and tests are unchanged. Note: the Stage B tests below
 * previously failed (the *old* Stage A self-matched unconditionally and
 * shadowed Stage B before it could ever run — see the removed
 * `synsetToBciIds` self-match logic in git history); they pass now purely
 * as a side effect of Stage A no longer touching [BlissLookup] at all, not
 * because Stage B's own logic changed. See the Stage B KDoc in
 * [BlissSemanticComposer] for why it is still effectively a no-op in real
 * production usage despite being reachable in this isolated test.
 */
@DisplayName("BlissSemanticComposer")
class BlissSemanticComposerTest {

    private val fakeContext: Context = mock<Context>().also { ctx ->
        whenever(ctx.applicationContext).thenReturn(ctx)
        whenever(ctx.packageName).thenReturn("com.blueapps.egyptianwriter.test")
    }

    private lateinit var lookup:   BlissLookup
    private lateinit var wordNet:  WordNetIndex
    private lateinit var composer: BlissSemanticComposer

    @BeforeEach
    fun setUp() {
        resetBlissLookupSingleton()
        lookup = BlissLookup.getInstance(fakeContext)
        // Stage B fixture design (unchanged by the Stage A redesign):
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
                MARE_ID       to "sea",
                BOAT_ID       to "boat",
                CLASSIFIER_ID to "container",
                SPECIFIER_ID  to "big_house"
            ),
            synsets = mapOf(
                CLASSIFIER_ID to SPECIFIER_SYNSET,
                SPECIFIER_ID  to SPECIFIER_SYNSET
            ),
            lexicon = mapOf(
                "big_house" to SPECIFIER_ID
            )
        )

        wordNet = WordNetIndex(fakeContext)
        // Stage A fixture:
        // - "oceano" shares OCEANO_SYNSET directly with a Bliss-linked
        //   synonym (MARE_ID) -> level-0 hit, no hypernym climbing needed.
        // - "veliero" has its own synset (VELIERO_SYNSET) with NO Bliss hit,
        //   but its direct hypernym (BOAT_SYNSET) does -> level-1 hit.
        // - "xyzzy" is absent from word2synsets entirely -> WordNet lookup
        //   itself misses, same as Stage C's fixture expects.
        injectWordNetTables(
            wordNet,
            word2synsets = mapOf(
                "oceano"  to listOf(OCEANO_SYNSET),
                "veliero" to listOf(VELIERO_SYNSET)
            ),
            synset2bliss = mapOf(
                OCEANO_SYNSET to listOf(MARE_ID),
                BOAT_SYNSET   to listOf(BOAT_ID)
            ),
            hypernyms = mapOf(
                VELIERO_SYNSET to listOf(BOAT_SYNSET)
            )
        )

        composer = BlissSemanticComposer(lookup, wordNet)
    }

    // ── Stage A ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("Stage A — WordNet synonym/hypernym substitution")
    inner class StageA {

        @Test @DisplayName("direct synonym (level 0): returns a single-component ComposedBlissWord via SYNONYM_SYNSET")
        fun directSynonymHit() {
            val result = composer.composeStructured("oceano", "it")

            assertNotNull(result, "Expected non-null ComposedBlissWord for a word with a direct WordNet synonym")
            assertEquals(CompositionPath.SYNONYM_SYNSET, result!!.compositionPath)
            assertEquals(1, result.components.size)
            assertEquals("it", result.sourceLang)
        }

        @Test @DisplayName("direct synonym: component carries the substitute's BCI-AV id, not a made-up one")
        fun directSynonymCarriesSubstituteId() {
            val result = composer.composeStructured("oceano", "it")!!
            assertEquals(MARE_ID, result.components.first().symbol.bciAvId)
        }

        @Test @DisplayName("hypernym (level 1): resolves via the direct hypernym's Bliss symbol when no direct synonym has one")
        fun hypernymLevel1Hit() {
            val result = composer.composeStructured("veliero", "it")

            assertNotNull(result, "Expected non-null ComposedBlissWord via hypernym climbing")
            assertEquals(BOAT_ID, result!!.components.first().symbol.bciAvId)
            assertEquals(CompositionPath.SYNONYM_SYNSET, result.compositionPath)
        }

        @Test @DisplayName("component lemma is the substitute's canonical name, not the original word")
        fun componentLemmaIsSubstituteName() {
            val result = composer.composeStructured("oceano", "it")!!
            assertEquals("sea", result.components.first().lemma, "lookup.nameOf(MARE_ID) == \"sea\" in this fixture")
        }

        @Test @DisplayName("word absent from WordNet data returns null (no synonym/hypernym path exists to try)")
        fun wordAbsentFromWordNetReturnsNull() {
            assertNull(composer.composeStructured("xyzzy", "it"))
        }

        @Test @DisplayName("Stage A is a no-op when no WordNetIndex is supplied")
        fun noWordNetIndexIsNoOp() {
            val composerWithoutWordNet = BlissSemanticComposer(lookup)
            assertNull(composerWithoutWordNet.composeStructured("oceano", "it"))
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

        @Test @DisplayName("returns a BlissSymbol with MatchType.SEMANTIC for a word with a WordNet substitute")
        fun returnsSemanticMatchType() {
            val symbol = composer.compose("oceano", "it")
            assertNotNull(symbol, "compose() shim must not return null when Stage A finds a substitute")
            assertEquals(MatchType.SEMANTIC, symbol!!.matchType)
        }

        @Test @DisplayName("is consistent with composeStructured().toFlatSymbol()")
        fun consistentWithStructuredToFlatSymbol() {
            val structured   = composer.composeStructured("oceano", "it")!!
            val fromStructured = structured.toFlatSymbol()
            val fromShim        = composer.compose("oceano", "it")!!

            assertEquals(fromStructured.bciAvId, fromShim.bciAvId)
            assertEquals(fromStructured.matchType, fromShim.matchType)
        }
    }

    companion object {
        // Stage A fixture
        private const val OCEANO_SYNSET  = "13776971-n" // shares this synset with "mare" (real PWN 3.0 offset)
        private const val VELIERO_SYNSET = "04194289-n" // "veliero"'s own synset, no direct Bliss hit
        private const val BOAT_SYNSET    = "02858304-n" // VELIERO_SYNSET's hypernym, has a Bliss hit
        private const val MARE_ID = 12335
        private const val BOAT_ID = 12336

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
