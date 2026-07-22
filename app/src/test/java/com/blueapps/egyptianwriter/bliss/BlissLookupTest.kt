package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.content.res.AssetManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.lang.reflect.Method

/**
 * Unit tests for [BlissLookup].
 *
 * Strategy: use reflection to (a) call private normaliseLang(), (b) inject
 * synthetic data into the [BlissLookup.Tables] snapshot via the shared
 * [injectBlissTables] helper, and (c) reset the singleton between tests via
 * [resetBlissLookupSingleton] (see `BlissTestUtils.kt`). No real Android
 * assets are opened.
 */
@DisplayName("BlissLookup — logic and state management")
class BlissLookupTest {

    private val fakeContext: Context = mock<Context>().also { ctx ->
        whenever(ctx.applicationContext).thenReturn(ctx)
        whenever(ctx.packageName).thenReturn("com.blueapps.egyptianwriter.test")
    }

    private lateinit var lookup: BlissLookup

    @BeforeEach
    fun setUp() {
        resetBlissLookupSingleton()
        lookup = BlissLookup.getInstance(fakeContext)
    }

    // ── reflection helpers ────────────────────────────────────────────────────

    private fun normaliseLang(code: String): String {
        val m: Method = BlissLookup::class.java
            .getDeclaredMethod("normaliseLang", String::class.java)
        m.isAccessible = true
        return m.invoke(lookup, code) as String
    }

    /**
     * Stubs `fakeContext.assets.open("bliss/bci_lexicon_<lang>.json")` to
     * return [jsonContent], so [loadLexicon] can be exercised against
     * synthetic asset data without touching the real bundled assets.
     */
    private fun stubLexiconAsset(lang: String, jsonContent: String) {
        val assetManager = mock<AssetManager>()
        whenever(assetManager.open("bliss/bci_lexicon_$lang.json"))
            .thenReturn(ByteArrayInputStream(jsonContent.toByteArray(Charsets.UTF_8)))
        whenever(fakeContext.assets).thenReturn(assetManager)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadLexicon(lang: String): Map<String, Int> {
        val m: Method = BlissLookup::class.java.getDeclaredMethod("loadLexicon", String::class.java)
        m.isAccessible = true
        return m.invoke(lookup, lang) as Map<String, Int>
    }

    // ── normaliseLang ─────────────────────────────────────────────────────────

    @Nested @DisplayName("normaliseLang")
    inner class NormaliseLang {

        @Test @DisplayName("Supported lower-case code returned as-is")
        fun supportedLowerCase() = assertEquals("it", normaliseLang("it"))

        @Test @DisplayName("Supported upper-case code is lower-cased")
        fun supportedUpperCase() = assertEquals("en", normaliseLang("EN"))

        @Test @DisplayName("Long locale string (it-IT) is truncated to first 2 chars")
        fun longLocale() = assertEquals("it", normaliseLang("it-IT"))

        @Test @DisplayName("Unsupported code falls back to 'it'")
        fun unsupportedFallback() = assertEquals("it", normaliseLang("zh"))

        @Test @DisplayName("Empty string falls back to 'it'")
        fun emptyFallback() = assertEquals("it", normaliseLang(""))

        @ParameterizedTest(name = "Supported lang [{0}] is preserved")
        @ValueSource(strings = ["it", "en", "de", "fr", "es", "nl", "pl", "pt"])
        fun allSupportedLangs(lang: String) = assertEquals(lang, normaliseLang(lang))
    }

    // ── reset() ───────────────────────────────────────────────────────────────

    @Nested @DisplayName("reset()")
    inner class Reset {

        @Test @DisplayName("reset() sets isReady = false")
        fun resetClearsIsReady() {
            val f = BlissLookup::class.java.getDeclaredField("isReady")
            f.isAccessible = true
            f.setBoolean(lookup, true)
            lookup.reset()
            assertFalse(lookup.isReady)
        }

        @Test @DisplayName("reset() sets currentLang = null")
        fun resetClearsCurrentLang() {
            val f = BlissLookup::class.java.getDeclaredField("currentLang")
            f.isAccessible = true
            f.set(lookup, "it")
            lookup.reset()
            assertNull(lookup.currentLang)
        }

        @Test @DisplayName("reset() empties all maps")
        fun resetEmptiesMaps() {
            injectBlissTables(
                lookup,
                lexicon    = mapOf("ciao" to 1),
                lemmaIndex = mapOf("andare" to 2),
                ngramIndex = mapOf("buon giorno" to 3)
            )
            lookup.reset()
            assertTrue(lookup.lexicon.isEmpty(),    "lexicon should be empty")
            assertTrue(lookup.lemmaIndex.isEmpty(), "lemmaIndex should be empty")
            assertTrue(lookup.ngramIndex.isEmpty(), "ngramIndex should be empty")
        }
    }

    // ── sync lookup helpers ───────────────────────────────────────────────────

    @Nested @DisplayName("Sync lookup helpers")
    inner class SyncLookup {

        @BeforeEach
        fun injectData() {
            injectBlissTables(
                lookup,
                names         = mapOf(12335 to "walk", 14990 to "run"),
                synsets       = mapOf(12335 to 202316L),
                lexicon       = mapOf("camminare" to 12335, "correre" to 14990),
                lemmaIndex    = mapOf("camminare" to 12335),
                lemmaPoSIndex = mapOf("camminare|V" to 12335),
                ngramIndex    = mapOf("buon giorno" to 99001)
            )
        }

        @Test @DisplayName("lookupSurface finds exact lower-case word")
        fun lookupSurfaceFound() = assertEquals(12335, lookup.lookupSurface("camminare"))

        @Test @DisplayName("lookupSurface is case-insensitive")
        fun lookupSurfaceCaseInsensitive() = assertEquals(12335, lookup.lookupSurface("CAMMINARE"))

        @Test @DisplayName("lookupSurface returns null for unknown word")
        fun lookupSurfaceMiss() = assertNull(lookup.lookupSurface("volare"))

        @Test @DisplayName("lookupLemma returns id for known lemma")
        fun lookupLemmaFound() = assertEquals(12335, lookup.lookupLemma("camminare"))

        @Test @DisplayName("lookupNgram returns id for known phrase")
        fun lookupNgramFound() = assertEquals(99001, lookup.lookupNgram("buon giorno"))

        @Test @DisplayName("lookupNgram is case-insensitive")
        fun lookupNgramCaseInsensitive() = assertEquals(99001, lookup.lookupNgram("Buon Giorno"))

        @Test @DisplayName("nameOf returns English name when present")
        fun nameOfKnown() = assertEquals("walk", lookup.nameOf(12335))

        @Test @DisplayName("nameOf returns id.toString() for unknown id")
        fun nameOfUnknown() = assertEquals("99999", lookup.nameOf(99999))

        @Test @DisplayName("synsetOf returns Long offset when present")
        fun synsetOfKnown() = assertEquals(202316L, lookup.synsetOf(12335))

        @Test @DisplayName("synsetOf returns -1L for unknown id")
        fun synsetOfUnknown() = assertEquals(-1L, lookup.synsetOf(0))

        @Test @DisplayName("lookupLemmaPos returns POS-specific id when key matches")
        fun lookupLemmaPosHit() = assertEquals(12335, lookup.lookupLemmaPos("camminare", "V"))

        @Test @DisplayName("lookupLemmaPos falls back to plain lemma when POS key misses")
        fun lookupLemmaPosLemmaFallback() = assertEquals(12335, lookup.lookupLemmaPos("camminare", "N"))
    }

    // ── loadLexicon (JSON asset parsing) ─────────────────────────────────────

    /**
     * ## Fix (audit EG, 2026-07-22)
     * `bci_lexicon_<lang>.json` entries have always been JSON arrays of
     * candidate ids (e.g. `"punto": [8486, 13867]`), never a bare scalar —
     * confirmed back to the commit that first populated these shards.
     * `loadLexicon` used to call `JSONObject.optInt(key, -1)`, which returns
     * the `-1` fallback for any non-numeric value including a `JSONArray`,
     * so [BlissLookup.lexicon] was silently empty for every language and
     * tier 3a (exact surface match) never matched anything. These tests
     * exercise the real asset-parsing path (via a stubbed
     * `Context.assets.open(...)`) instead of bypassing it through
     * [injectBlissTables] like the rest of this file, since that is exactly
     * the path the bug lived in and the reason it went uncaught.
     */
    @Nested @DisplayName("loadLexicon (JSON asset parsing)")
    inner class LoadLexicon {

        @Test @DisplayName("array-valued entry with a single candidate id is parsed")
        fun singleCandidateArray() {
            stubLexiconAsset("it", """{"virgola":[8487]}""")
            assertEquals(8487, loadLexicon("it")["virgola"])
        }

        @Test @DisplayName("array-valued entry with multiple candidates uses the first as primary")
        fun multiCandidateArrayUsesFirst() {
            stubLexiconAsset("it", """{"punto":[8486,13867]}""")
            assertEquals(8486, loadLexicon("it")["punto"])
        }

        @Test @DisplayName("keys are lower-cased")
        fun keysAreLowerCased() {
            stubLexiconAsset("it", """{"CIAO":[100]}""")
            assertEquals(100, loadLexicon("it")["ciao"])
        }

        @Test @DisplayName("empty-array entries are skipped")
        fun emptyArraySkipped() {
            stubLexiconAsset("it", """{"vuoto":[]}""")
            assertTrue(loadLexicon("it").isEmpty())
        }

        @Test @DisplayName("bare scalar entries are still accepted defensively")
        fun bareScalarStillAccepted() {
            stubLexiconAsset("it", """{"scalare":42}""")
            assertEquals(42, loadLexicon("it")["scalare"])
        }

        @Test @DisplayName("multiple entries parse independently")
        fun multipleEntries() {
            stubLexiconAsset(
                "it",
                """{"virgola":[8487],"punto":[8486,13867],"due punti":[8488]}"""
            )
            val result = loadLexicon("it")
            assertEquals(3, result.size)
            assertEquals(8487, result["virgola"])
            assertEquals(8486, result["punto"])
            assertEquals(8488, result["due punti"])
        }
    }

    // ── constants ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("Constants")
    inner class Constants {

        @Test @DisplayName("SUPPORTED_LANGS contains exactly the 8 declared languages")
        fun supportedLangsSet() {
            val expected = setOf("it", "en", "de", "fr", "es", "nl", "pl", "pt")
            assertEquals(expected, BlissLookup.SUPPORTED_LANGS)
        }

        @Test @DisplayName("getInstance() returns the same singleton instance")
        fun singletonIdentity() {
            val a = BlissLookup.getInstance(fakeContext)
            val b = BlissLookup.getInstance(fakeContext)
            assertSame(a, b)
        }

        @Test @DisplayName("LoadException preserves message and cause")
        fun loadExceptionFields() {
            val cause = java.io.IOException("asset missing")
            val ex    = BlissLookup.LoadException("Failed to load lang=it", cause)
            assertEquals("Failed to load lang=it", ex.message)
            assertSame(cause, ex.cause)
        }
    }
}
