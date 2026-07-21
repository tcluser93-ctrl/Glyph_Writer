package com.blueapps.egyptianwriter.bliss

import android.content.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Unit tests for [BlissLookup].
 *
 * Strategy: use reflection to (a) call private normaliseLang(), (b) inject
 * synthetic data into backing fields, and (c) reset the singleton between
 * tests.  No real Android assets are opened.
 *
 * ## Singleton reset — Kotlin companion object layout
 *
 * In Kotlin, a `companion object` with a `private var INSTANCE` is compiled so
 * that the JVM backing field lives as a *static* field on the **outer** class
 * (`BlissLookup`), not on the `Companion` inner class.  Therefore reflection
 * must target `BlissLookup::class.java`, not `BlissLookup.Companion::class.java`.
 *
 * Verified field name: `INSTANCE` (javap output on BlissLookup.class).
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
        resetSingleton()
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
     * Builds a `BlissLookup.Tables` instance via reflection and injects it
     * into the private `_tables` field.
     *
     * ## Fix (audit EG, 2026-07-21)
     * [BlissLookup] used to expose six independent `@Volatile` map fields
     * (`_names`, `_lexicon`, …), which this test suite injected individually
     * via [injectField]. They are now consolidated into one immutable
     * `Tables` snapshot published through a single `_tables` field (see its
     * KDoc in `BlissLookup.kt` for the atomicity fix this enables). This
     * helper constructs that snapshot reflectively — `Tables` is a private
     * nested data class, so its declared (not public) constructor is used.
     */
    private fun injectTables(
        names:         Map<Int, String> = emptyMap(),
        synsets:       Map<Int, Long>   = emptyMap(),
        lexicon:       Map<String, Int> = emptyMap(),
        lemmaIndex:    Map<String, Int> = emptyMap(),
        lemmaPoSIndex: Map<String, Int> = emptyMap(),
        ngramIndex:    Map<String, Int> = emptyMap()
    ) {
        val tablesClass = Class.forName("com.blueapps.egyptianwriter.bliss.BlissLookup\$Tables")
        val ctor = tablesClass.getDeclaredConstructor(
            Map::class.java, Map::class.java, Map::class.java,
            Map::class.java, Map::class.java, Map::class.java
        )
        ctor.isAccessible = true
        val tables = ctor.newInstance(names, synsets, lexicon, lemmaIndex, lemmaPoSIndex, ngramIndex)
        val f: Field = BlissLookup::class.java.getDeclaredField("_tables")
        f.isAccessible = true
        f.set(lookup, tables)
    }

    /**
     * Reset the singleton INSTANCE.
     *
     * Kotlin compiles `companion object { private var INSTANCE: BlissLookup? }`
     * as a **static** field on the outer JVM class (`BlissLookup`), not on the
     * `Companion` inner class.  We must therefore call
     * `BlissLookup::class.java.getDeclaredField("INSTANCE")` — targeting the
     * outer class — to find it.
     */
    private fun resetSingleton() {
        val outerClass: Class<*> = BlissLookup::class.java
        val f: Field = outerClass.getDeclaredField("INSTANCE")
        f.isAccessible = true
        (f.get(null) as? BlissLookup)?.reset()
        f.set(null, null)
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
            injectTables(
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
            injectTables(
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
