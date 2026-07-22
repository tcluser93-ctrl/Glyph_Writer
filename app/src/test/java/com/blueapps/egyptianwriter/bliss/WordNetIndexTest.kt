package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.content.res.AssetManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream

/**
 * Unit tests for [WordNetIndex] — the Stage A substitution data layer
 * introduced by the EG audit's tier 3g redesign (2026-07-22). See
 * [WordNetIndex]'s KDoc and `Report_EG_Tier3g_Opzioni_A_D.md` for the
 * design rationale.
 *
 * `findSubstitute` tests use [injectWordNetTables] (the [WordNetIndex]
 * analogue of [injectBlissTables]) to seed synthetic data directly,
 * mirroring [BlissLookupTest]'s strategy. The `load()` asset-parsing tests
 * stub `Context.assets.open(...)` instead, exercising the real JSON parsing
 * path — see [BlissLookupTest.LoadLexicon]'s KDoc for why that distinction
 * matters (asset-parsing bugs live exactly in the code that
 * reflection-injection bypasses).
 */
@DisplayName("WordNetIndex")
class WordNetIndexTest {

    private val fakeContext: Context = mock<Context>().also { ctx ->
        whenever(ctx.applicationContext).thenReturn(ctx)
    }

    private lateinit var index: WordNetIndex

    @BeforeEach
    fun setUp() {
        index = WordNetIndex(fakeContext)
    }

    // ── findSubstitute ────────────────────────────────────────────────────────

    @Nested @DisplayName("findSubstitute")
    inner class FindSubstitute {

        @Test @DisplayName("returns null when the word is absent from word2synsets")
        fun wordAbsent() {
            injectWordNetTables(index, word2synsets = mapOf("mare" to listOf("S1")))
            assertNull(index.findSubstitute("oceano"))
        }

        @Test @DisplayName("level 0: returns the Bliss id when the word's own synset has a direct hit")
        fun directSynonymLevel0() {
            injectWordNetTables(
                index,
                word2synsets = mapOf("oceano" to listOf("S1")),
                synset2bliss = mapOf("S1" to listOf(100))
            )
            val result = index.findSubstitute("oceano")
            assertNotNull(result)
            assertEquals(100, result!!.bciAvId)
            assertEquals("S1", result.synset)
            assertEquals(0, result.level)
        }

        @Test @DisplayName("lookup is case-insensitive")
        fun caseInsensitive() {
            injectWordNetTables(
                index,
                word2synsets = mapOf("oceano" to listOf("S1")),
                synset2bliss = mapOf("S1" to listOf(100))
            )
            assertEquals(100, index.findSubstitute("OCEANO")?.bciAvId)
        }

        @Test @DisplayName("level 1: climbs one hypernym hop when the word's own synset has no direct hit")
        fun hypernymLevel1() {
            injectWordNetTables(
                index,
                word2synsets = mapOf("veliero" to listOf("S_VELIERO")),
                synset2bliss = mapOf("S_BOAT" to listOf(200)),
                hypernyms    = mapOf("S_VELIERO" to listOf("S_BOAT"))
            )
            val result = index.findSubstitute("veliero")
            assertNotNull(result)
            assertEquals(200, result!!.bciAvId)
            assertEquals(1, result.level)
        }

        @Test @DisplayName("level 2: climbs two hypernym hops when neither the word nor its direct hypernym has a hit")
        fun hypernymLevel2() {
            injectWordNetTables(
                index,
                word2synsets = mapOf("caravella" to listOf("S_CARAVELLA")),
                synset2bliss = mapOf("S_VEHICLE" to listOf(300)),
                hypernyms = mapOf(
                    "S_CARAVELLA" to listOf("S_SHIP"),
                    "S_SHIP"      to listOf("S_VEHICLE")
                )
            )
            val result = index.findSubstitute("caravella")
            assertNotNull(result)
            assertEquals(300, result!!.bciAvId)
            assertEquals(2, result.level)
        }

        @Test @DisplayName("hop cap: a hit beyond MAX_HYPERNYM_LEVELS is not found")
        fun beyondHopCapReturnsNull() {
            // Chain is 3 hops deep (S_A -> S_B -> S_C -> S_D); the Bliss hit
            // sits on S_D, one hop past the MAX_HYPERNYM_LEVELS=2 cap.
            injectWordNetTables(
                index,
                word2synsets = mapOf("parola" to listOf("S_A")),
                synset2bliss = mapOf("S_D" to listOf(400)),
                hypernyms = mapOf(
                    "S_A" to listOf("S_B"),
                    "S_B" to listOf("S_C"),
                    "S_C" to listOf("S_D")
                )
            )
            assertNull(index.findSubstitute("parola"))
        }

        @Test @DisplayName("prefers the lowest available level even when a higher level would also match")
        fun prefersLowestLevel() {
            // Both the word's own synset (level 0) AND its hypernym
            // (level 1) have Bliss hits; level 0 must win.
            injectWordNetTables(
                index,
                word2synsets = mapOf("mare" to listOf("S_MARE")),
                synset2bliss = mapOf(
                    "S_MARE" to listOf(100),
                    "S_WATER_BODY" to listOf(999)
                ),
                hypernyms = mapOf("S_MARE" to listOf("S_WATER_BODY"))
            )
            val result = index.findSubstitute("mare")
            assertEquals(100, result?.bciAvId)
            assertEquals(0, result?.level)
        }

        @Test @DisplayName("multiple own synsets: the first with a hit wins (first-sense order)")
        fun multipleOwnSynsetsFirstSenseWins() {
            injectWordNetTables(
                index,
                word2synsets = mapOf("punto" to listOf("S_NO_HIT", "S_HIT")),
                synset2bliss = mapOf("S_HIT" to listOf(500))
            )
            val result = index.findSubstitute("punto")
            assertEquals(500, result?.bciAvId)
            assertEquals("S_HIT", result?.synset)
        }
    }

    // ── load() / reset() ─────────────────────────────────────────────────────

    @Nested @DisplayName("load / reset")
    inner class LoadReset {

        /**
         * Stubs `fakeContext.assets` with an [AssetManager] whose `open()`
         * throws [java.io.IOException] for every path — i.e. a language with
         * no `wordnet_build.py` output bundled. `readJsonObjectOrNull` only
         * catches [java.io.IOException] (not an unstubbed-mock NPE), so this
         * distinction is what the "missing assets degrade gracefully" tests
         * are actually verifying — a completely unstubbed `Context.assets`
         * mock would NPE instead, exercising nothing about the real code.
         */
        private fun stubNoAssets() {
            val assetManager = mock<AssetManager>()
            whenever(assetManager.open(org.mockito.kotlin.any()))
                .thenThrow(java.io.IOException("asset not found (test stub)"))
            whenever(fakeContext.assets).thenReturn(assetManager)
        }

        @Test @DisplayName("isLoaded is false before the first load()")
        fun notLoadedInitially() {
            assertFalse(index.isLoaded)
            assertNull(index.currentLang)
        }

        @Test @DisplayName("missing per-language assets: load() leaves maps empty rather than throwing")
        fun missingAssetsDegradesGracefully() {
            // Simulates a language with no wordnet_build.py output bundled:
            // AssetManager.open() throws IOException for every path.
            stubNoAssets()
            index.load("de")
            assertTrue(index.isLoaded)
            assertEquals("de", index.currentLang)
            assertNull(index.findSubstitute("irgendein wort"))
        }

        @Test @DisplayName("load() parses real word2synsets/synset2bliss/hypernyms JSON assets")
        fun loadParsesRealAssets() {
            val assetManager = mock<AssetManager>()
            whenever(assetManager.open("wordnet/word2synsets_it.json"))
                .thenReturn(ByteArrayInputStream("""{"oceano":["S1"]}""".toByteArray()))
            whenever(assetManager.open("wordnet/synset2bliss_it.json"))
                .thenReturn(ByteArrayInputStream("""{"S1":[100]}""".toByteArray()))
            whenever(assetManager.open("wordnet/hypernyms.json"))
                .thenReturn(ByteArrayInputStream("""{}""".toByteArray()))
            whenever(fakeContext.assets).thenReturn(assetManager)

            index.load("it")

            assertEquals("it", index.currentLang)
            assertEquals(100, index.findSubstitute("oceano")?.bciAvId)
        }

        @Test @DisplayName("reset() clears currentLang and empties the substitution data")
        fun resetClears() {
            stubNoAssets()
            injectWordNetTables(
                index,
                word2synsets = mapOf("oceano" to listOf("S1")),
                synset2bliss = mapOf("S1" to listOf(100))
            )
            index.load("it") // sets currentLang; assets aren't stubbed so maps end up empty either way
            index.reset()
            assertFalse(index.isLoaded)
            assertNull(index.currentLang)
            assertNull(index.findSubstitute("oceano"))
        }
    }
}
