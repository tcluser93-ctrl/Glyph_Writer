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
 * ## Update (audit EG, 2026-07-22) — Stage A/B redesign
 * Neither stage re-queries [BlissLookup] anymore (see [BlissSemanticComposer]'s
 * KDoc for why the original Patch 5 versions of both were permanently
 * unreachable, and Stage B additionally self-contradictory even in
 * isolation). Both now query the same [WordNetIndex]: Stage A accepts only
 * a direct-synonym (level 0) hit; Stage B accepts only a hypernym (level
 * >= 1) hit and composes it with the literal word as a second, unresolved
 * component. Fixtures below are seeded via the shared [injectWordNetTables]
 * / [injectBlissTables] reflection helpers.
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
        injectBlissTables(
            lookup,
            names = mapOf(
                MARE_ID to "sea",
                BOAT_ID to "boat"
            )
        )

        wordNet = WordNetIndex(fakeContext)
        // - "oceano" shares OCEANO_SYNSET directly with a Bliss-linked
        //   synonym (MARE_ID) -> level-0 hit -> Stage A.
        // - "veliero" has its own synset (VELIERO_SYNSET) with NO Bliss hit,
        //   but its direct hypernym (BOAT_SYNSET) does -> level-1 hit -> Stage B.
        // - "xyzzy" is absent from word2synsets entirely -> both stages miss.
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

    @Nested @DisplayName("Stage A — WordNet direct-synonym substitution")
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

        @Test @DisplayName("component lemma is the substitute's canonical name, not the original word")
        fun componentLemmaIsSubstituteName() {
            val result = composer.composeStructured("oceano", "it")!!
            assertEquals("sea", result.components.first().lemma, "lookup.nameOf(MARE_ID) == \"sea\" in this fixture")
        }

        @Test @DisplayName("does NOT handle a hypernym-only (level >= 1) hit — that's Stage B's job")
        fun hypernymOnlyHitIsNotHandledByStageA() {
            // "veliero" has no level-0 synonym, only a level-1 hypernym
            // (BOAT_SYNSET) — composeStructured() still returns non-null
            // overall (Stage B picks it up), but via a *different* path
            // than SYNONYM_SYNSET; see StageB.hypernymLevel1ReturnsTwoComponents.
            val result = composer.composeStructured("veliero", "it")!!
            assertEquals(CompositionPath.SEMANTIC_DECOMPOSITION, result.compositionPath,
                "A hypernym-only hit must go through Stage B, not Stage A")
        }

        @Test @DisplayName("word absent from WordNet data returns null (no synonym/hypernym path exists to try)")
        fun wordAbsentFromWordNetReturnsNull() {
            assertNull(composer.composeStructured("xyzzy", "it"))
        }

        @Test @DisplayName("Stage A/B are a no-op when no WordNetIndex is supplied")
        fun noWordNetIndexIsNoOp() {
            val composerWithoutWordNet = BlissSemanticComposer(lookup)
            assertNull(composerWithoutWordNet.composeStructured("oceano", "it"))
        }
    }

    // ── Stage B ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("Stage B — hypernym classifier + literal specifier")
    inner class StageB {

        @Test @DisplayName("hypernym (level 1): returns a two-component ComposedBlissWord via SEMANTIC_DECOMPOSITION")
        fun hypernymLevel1ReturnsTwoComponents() {
            val result = composer.composeStructured("veliero", "it")

            assertNotNull(result, "Expected non-null ComposedBlissWord via hypernym classifier + specifier")
            assertEquals(CompositionPath.SEMANTIC_DECOMPOSITION, result!!.compositionPath)
            assertEquals(2, result.components.size)
        }

        @Test @DisplayName("classifier component carries the hypernym's real Bliss id and SEMANTIC match type")
        fun classifierComponentIsRealBlissSymbol() {
            val result = composer.composeStructured("veliero", "it")!!
            val classifier = result.components[0]
            assertEquals(BOAT_ID, classifier.symbol.bciAvId)
            assertEquals(MatchType.SEMANTIC, classifier.symbol.matchType)
            assertEquals("boat", classifier.lemma, "lookup.nameOf(BOAT_ID) == \"boat\" in this fixture")
        }

        @Test @DisplayName("specifier component carries the literal original word, unresolved (not a made-up Bliss id)")
        fun specifierComponentIsLiteralWord() {
            val result = composer.composeStructured("veliero", "it")!!
            val specifier = result.components[1]
            assertEquals(MatchType.UNKNOWN, specifier.symbol.matchType,
                "The specifier must never claim a Bliss id that doesn't exist for this word")
            assertEquals("veliero", specifier.symbol.sourceWord)
            assertEquals("veliero", specifier.lemma)
        }

        @Test @DisplayName("does NOT handle a direct-synonym (level 0) hit — that's Stage A's job")
        fun directSynonymHitIsNotHandledByStageB() {
            val result = composer.composeStructured("oceano", "it")!!
            assertEquals(1, result.components.size,
                "A direct synonym must resolve via Stage A as a single component, not Stage B's two")
        }

        @Test @DisplayName("word absent from WordNet data returns null")
        fun wordAbsentFromWordNetReturnsNull() {
            assertNull(composer.composeStructured("xyzzy", "it"))
        }
    }

    // ── Stage C ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("Stage C — orthographic fallback")
    inner class StageC {

        @Test @DisplayName("disabled by default: unresolvable word returns null")
        fun disabledByDefaultReturnsNull() {
            // "xyzzy" is absent from both the lexicon and the WordNet data,
            // and enableOrthographicFallback defaults to false, so Stage C
            // never even runs — Stage A/B simply fail to resolve the token.
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
        private const val OCEANO_SYNSET  = "13776971-n" // shares this synset with "mare" (real PWN 3.0 offset)
        private const val VELIERO_SYNSET = "04194289-n" // "veliero"'s own synset, no direct Bliss hit
        private const val BOAT_SYNSET    = "02858304-n" // VELIERO_SYNSET's hypernym, has a Bliss hit
        private const val MARE_ID = 12335
        private const val BOAT_ID = 12336
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
