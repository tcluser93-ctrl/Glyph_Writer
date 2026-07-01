package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Unit tests for [BlissLookup].
 *
 * Strategy: use reflection to (a) call private normaliseLang(), (b) inject
 * synthetic data into backing fields, and (c) reset the singleton between
 * tests.  No real Android assets are opened — the fakeContext throws on any
 * I/O attempt so accidental asset access fails loudly.
 */
@DisplayName("BlissLookup — logic and state management")
class BlissLookupTest {

    // ── fake Android context ──────────────────────────────────────────────────
    private val fakeContext: android.content.Context =
        object : android.content.Context() {
            override fun getApplicationContext() = this
            override fun getAssets(): android.content.res.AssetManager =
                throw UnsupportedOperationException("Test must not open assets")
            override fun getPackageName() = "com.blueapps.egyptianwriter.test"
            override fun getResources()  = throw UnsupportedOperationException()
            override fun getPackageManager() = throw UnsupportedOperationException()
            override fun getContentResolver() = throw UnsupportedOperationException()
            override fun getMainLooper(): android.os.Looper = android.os.Looper.getMainLooper()
            override fun getSystemService(name: String): Any? = null
            override fun checkPermission(permission: String, pid: Int, uid: Int) = -1
            override fun checkCallingOrSelfPermission(permission: String) = -1
            override fun getApplicationInfo() = throw UnsupportedOperationException()
            override fun bindService(i: android.content.Intent, c: android.content.ServiceConnection, f: Int) = false
            override fun unbindService(c: android.content.ServiceConnection) = Unit
            override fun startActivity(i: android.content.Intent) = Unit
            override fun startService(i: android.content.Intent): android.content.ComponentName? = null
            override fun stopService(i: android.content.Intent) = false
            override fun sendBroadcast(i: android.content.Intent) = Unit
            override fun registerReceiver(r: android.content.BroadcastReceiver?, f: android.content.IntentFilter): android.content.Intent? = null
            override fun unregisterReceiver(r: android.content.BroadcastReceiver) = Unit
            override fun createPackageContext(p: String, f: Int): android.content.Context = this
            override fun getDir(n: String, m: Int): java.io.File = throw UnsupportedOperationException()
            override fun getFilesDir(): java.io.File = throw UnsupportedOperationException()
            override fun getCacheDir(): java.io.File = throw UnsupportedOperationException()
            override fun getDatabasePath(n: String): java.io.File = throw UnsupportedOperationException()
            override fun openFileInput(n: String): java.io.FileInputStream = throw UnsupportedOperationException()
            override fun openFileOutput(n: String, m: Int): java.io.FileOutputStream = throw UnsupportedOperationException()
            override fun getSharedPreferences(n: String, m: Int): android.content.SharedPreferences = throw UnsupportedOperationException()
            override fun deleteFile(n: String) = false
            override fun getFileStreamPath(n: String): java.io.File = throw UnsupportedOperationException()
            override fun getClassLoader(): ClassLoader = javaClass.classLoader!!
            override fun getTheme(): android.content.res.Resources.Theme = throw UnsupportedOperationException()
            override fun setTheme(r: Int) = Unit
            override fun obtainStyledAttributes(a: android.util.AttributeSet?, s: IntArray, d: Int, r: Int): android.content.res.TypedArray = throw UnsupportedOperationException()
            override fun getString(r: Int) = ""
            override fun getText(r: Int): CharSequence = ""
            override fun getColor(id: Int) = 0
            override fun getColorStateList(id: Int): android.content.res.ColorStateList? = null
            override fun getDrawable(id: Int): android.graphics.drawable.Drawable? = null
        }

    private lateinit var lookup: BlissLookup

    @BeforeEach
    fun setUp() {
        resetSingleton()
        lookup = BlissLookup.getInstance(fakeContext)
    }

    // ── reflection helpers ────────────────────────────────────────────────────

    private fun normaliseLang(code: String): String {
        val m: Method = BlissLookup::class.java.getDeclaredMethod("normaliseLang", String::class.java)
        m.isAccessible = true
        return m.invoke(lookup, code) as String
    }

    /** Inject a value into a private backing field of the lookup instance. */
    private fun injectField(fieldName: String, value: Any) {
        val f: Field = BlissLookup::class.java.getDeclaredField(fieldName)
        f.isAccessible = true
        f.set(lookup, value)
    }

    /**
     * Reset the singleton by reaching into the Companion object's INSTANCE field.
     * The Companion object is the static inner class generated by Kotlin;
     * its fields live on the Companion class, not on BlissLookup itself.
     */
    private fun resetSingleton() {
        // The Kotlin companion object is accessible as BlissLookup.Companion
        val companionClass = BlissLookup.Companion::class.java
        val f: Field = companionClass.getDeclaredField("INSTANCE")
        f.isAccessible = true
        (f.get(BlissLookup.Companion) as? BlissLookup)?.reset()
        f.set(BlissLookup.Companion, null)
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
            // isReady has `private set` — inject via reflection
            val f = BlissLookup::class.java.getDeclaredField("isReady")
            f.isAccessible = true
            f.setBoolean(lookup, true)
            lookup.reset()
            assertFalse(lookup.isReady)
        }

        @Test @DisplayName("reset() sets currentLang = null")
        fun resetClearsCurrentLang() {
            // currentLang has `private set` — inject via reflection
            val f = BlissLookup::class.java.getDeclaredField("currentLang")
            f.isAccessible = true
            f.set(lookup, "it")
            lookup.reset()
            assertNull(lookup.currentLang)
        }

        @Test @DisplayName("reset() empties all maps")
        fun resetEmptiesMaps() {
            injectField("_lexicon",    mapOf("ciao" to 1))
            injectField("_lemmaIndex", mapOf("andare" to 2))
            injectField("_ngramIndex", mapOf("buon giorno" to 3))
            lookup.reset()
            assertTrue(lookup.lexicon.isEmpty(),   "lexicon should be empty")
            assertTrue(lookup.lemmaIndex.isEmpty(), "lemmaIndex should be empty")
            assertTrue(lookup.ngramIndex.isEmpty(), "ngramIndex should be empty")
        }
    }

    // ── sync lookup helpers ───────────────────────────────────────────────────

    @Nested @DisplayName("Sync lookup helpers")
    inner class SyncLookup {

        @BeforeEach
        fun injectData() {
            injectField("_names",         mapOf(12335 to "walk", 14990 to "run"))
            injectField("_synsets",       mapOf(12335 to 202316L))
            injectField("_lexicon",       mapOf("camminare" to 12335, "correre" to 14990))
            injectField("_lemmaIndex",    mapOf("camminare" to 12335))
            injectField("_lemmaPoSIndex", mapOf("camminare|V" to 12335))
            injectField("_ngramIndex",    mapOf("buon giorno" to 99001))
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
