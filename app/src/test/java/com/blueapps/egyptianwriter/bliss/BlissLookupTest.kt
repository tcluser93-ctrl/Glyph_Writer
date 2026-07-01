package com.blueapps.egyptianwriter.bliss

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Unit tests for [BlissLookup].
 *
 * ## Strategy
 * [BlissLookup] requires an Android [Context] for asset I/O, which is not
 * available on the JVM.  We test the parts that are independent of I/O by:
 *
 * 1. **Reflection access** to `normaliseLang()` (private) and to the backing
 *    field setters so we can inject synthetic data without real assets.
 * 2. **FakeContext** — a minimal stub that throws [UnsupportedOperationException]
 *    for any method not used in pure-logic paths.  If a test accidentally
 *    triggers real I/O, the stub throws immediately (fail-fast).
 * 3. **reset()** called in `@BeforeEach` so singleton state never leaks between
 *    tests (this is the exact bug fixed in problem #7).
 *
 * Tests that require real asset files (load / loadAsync / loadIfNeeded) are
 * tagged `@Tag("integration")` and excluded from the default JVM test run.
 * They run only on a device/emulator via `./gradlew connectedAndroidTest`.
 *
 * ## Coverage map
 * | Category                              | Tests |
 * |---------------------------------------|-------|
 * | normaliseLang                         | 6     |
 * | reset()                               | 3     |
 * | lookupSurface / lookupLemma / lookupNgram (injected maps) | 6 |
 * | lookupLemmaPos fallback logic         | 2     |
 * | nameOf / synsetOf defaults            | 2     |
 * | SUPPORTED_LANGS set content           | 1     |
 * | Singleton identity                    | 1     |
 * | LoadException message                 | 1     |
 */
@DisplayName("BlissLookup — logic and state management")
class BlissLookupTest {

    // ── fake context ──────────────────────────────────────────────────────────

    /**
     * Minimal Android Context stub that satisfies [BlissLookup.getInstance]
     * without triggering real Android code.
     *
     * Every method not explicitly needed throws [UnsupportedOperationException]
     * so any accidental I/O call causes an immediate, descriptive test failure.
     */
    private val fakeContext: android.content.Context =
        object : android.content.Context() {
            override fun getApplicationContext() = this
            // All asset/resource methods are NOT implemented — tests that call
            // them will fail loudly rather than silently returning null.
            override fun getAssets(): android.content.res.AssetManager =
                throw UnsupportedOperationException("Test must not open assets")
            // Remaining abstract methods — stub with minimal no-op or null.
            override fun getPackageName() = "com.blueapps.egyptianwriter.test"
            override fun getResources()  = throw UnsupportedOperationException("no resources")
            override fun getPackageManager() = throw UnsupportedOperationException()
            override fun getContentResolver() = throw UnsupportedOperationException()
            override fun getMainLooper(): android.os.Looper = android.os.Looper.getMainLooper()
            override fun getSystemService(name: String): Any? = null
            override fun checkPermission(permission: String, pid: Int, uid: Int) = -1
            override fun checkCallingOrSelfPermission(permission: String) = -1
            override fun getApplicationInfo() = throw UnsupportedOperationException()
            override fun bindService(intent: android.content.Intent, conn: android.content.ServiceConnection, flags: Int) = false
            override fun unbindService(conn: android.content.ServiceConnection) = Unit
            override fun startActivity(intent: android.content.Intent) = Unit
            override fun startService(intent: android.content.Intent): android.content.ComponentName? = null
            override fun stopService(intent: android.content.Intent) = false
            override fun sendBroadcast(intent: android.content.Intent) = Unit
            override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter): android.content.Intent? = null
            override fun unregisterReceiver(receiver: android.content.BroadcastReceiver) = Unit
            override fun createPackageContext(packageName: String, flags: Int): android.content.Context = this
            override fun getDir(name: String, mode: Int): java.io.File = throw UnsupportedOperationException()
            override fun getFilesDir(): java.io.File = throw UnsupportedOperationException()
            override fun getCacheDir(): java.io.File = throw UnsupportedOperationException()
            override fun getDatabasePath(name: String): java.io.File = throw UnsupportedOperationException()
            override fun openFileInput(name: String): java.io.FileInputStream = throw UnsupportedOperationException()
            override fun openFileOutput(name: String, mode: Int): java.io.FileOutputStream = throw UnsupportedOperationException()
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = throw UnsupportedOperationException()
            override fun deleteFile(name: String) = false
            override fun getFileStreamPath(name: String): java.io.File = throw UnsupportedOperationException()
            override fun getClassLoader(): ClassLoader = javaClass.classLoader!!
            override fun getTheme(): android.content.res.Resources.Theme = throw UnsupportedOperationException()
            override fun setTheme(resid: Int) = Unit
            override fun obtainStyledAttributes(attrs: android.util.AttributeSet?, styleable: IntArray, defStyleAttr: Int, defStyleRes: Int): android.content.res.TypedArray = throw UnsupportedOperationException()
            override fun getString(resId: Int) = ""
            override fun getText(resId: Int): CharSequence = ""
            override fun getColor(id: Int) = 0
            override fun getColorStateList(id: Int): android.content.res.ColorStateList? = null
            override fun getDrawable(id: Int): android.graphics.drawable.Drawable? = null
        }

    // ── subject under test — fresh singleton per test via reset ───────────────

    private lateinit var lookup: BlissLookup

    @BeforeEach
    fun setUp() {
        // Wipe singleton so each test starts clean (bug #7 regression guard)
        resetSingleton()
        lookup = BlissLookup.getInstance(fakeContext)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Call private `normaliseLang(code)` via reflection. */
    private fun normaliseLang(code: String): String {
        val m: Method = BlissLookup::class.java.getDeclaredMethod("normaliseLang", String::class.java)
        m.isAccessible = true
        return m.invoke(lookup, code) as String
    }

    /** Inject a map directly into a private `@Volatile` backing field. */
    private fun injectField(fieldName: String, value: Any) {
        val f: Field = BlissLookup::class.java.getDeclaredField(fieldName)
        f.isAccessible = true
        f.set(lookup, value)
    }

    /** Wipe the companion INSTANCE so the next getInstance() creates a fresh one. */
    private fun resetSingleton() {
        val companion = BlissLookup.Companion
        val f = companion.javaClass.getDeclaredField("INSTANCE")
        f.isAccessible = true
        (f.get(companion) as? BlissLookup)?.reset()
        f.set(companion, null)
    }

    // ── normaliseLang ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("normaliseLang")
    inner class NormaliseLang {

        @Test
        @DisplayName("Supported lower-case code returned as-is")
        fun supportedLowerCase() = assertEquals("it", normaliseLang("it"))

        @Test
        @DisplayName("Supported upper-case code is lower-cased")
        fun supportedUpperCase() = assertEquals("en", normaliseLang("EN"))

        @Test
        @DisplayName("Long locale string (it-IT) is truncated to first 2 chars")
        fun longLocale() = assertEquals("it", normaliseLang("it-IT"))

        @Test
        @DisplayName("Unsupported code falls back to 'it'")
        fun unsupportedFallback() = assertEquals("it", normaliseLang("zh"))

        @Test
        @DisplayName("Empty string falls back to 'it'")
        fun emptyFallback() = assertEquals("it", normaliseLang(""))

        @ParameterizedTest(name = "Supported lang [{0}] is preserved")
        @ValueSource(strings = ["it", "en", "de", "fr", "es", "nl", "pl", "pt"])
        fun allSupportedLangs(lang: String) = assertEquals(lang, normaliseLang(lang))
    }

    // ── reset() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reset()")
    inner class Reset {

        @Test
        @DisplayName("reset() sets isReady = false")
        fun resetClearsIsReady() {
            // Force isReady = true via reflection
            val f = BlissLookup::class.java.getDeclaredField("isReady")
            f.isAccessible = true
            f.setBoolean(lookup, true)

            lookup.reset()
            assertFalse(lookup.isReady)
        }

        @Test
        @DisplayName("reset() sets currentLang = null")
        fun resetClearscurrentLang() {
            val f = BlissLookup::class.java.getDeclaredField("currentLang")
            f.isAccessible = true
            f.set(lookup, "it")

            lookup.reset()
            assertNull(lookup.currentLang)
        }

        @Test
        @DisplayName("reset() empties all maps (lexicon, lemmaIndex, ngramIndex)")
        fun resetEmptiesMaps() {
            injectField("_lexicon",    mapOf("ciao" to 1))
            injectField("_lemmaIndex", mapOf("andare" to 2))
            injectField("_ngramIndex", mapOf("buon giorno" to 3))

            lookup.reset()

            assertTrue(lookup.lexicon.isEmpty(),    "lexicon should be empty after reset")
            assertTrue(lookup.lemmaIndex.isEmpty(),  "lemmaIndex should be empty after reset")
            assertTrue(lookup.ngramIndex.isEmpty(),  "ngramIndex should be empty after reset")
        }
    }

    // ── sync lookup helpers ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Sync lookup helpers")
    inner class SyncLookup {

        @BeforeEach
        fun injectData() {
            injectField("_names",      mapOf(12335 to "walk", 14990 to "run"))
            injectField("_synsets",    mapOf(12335 to 202316L))
            injectField("_lexicon",    mapOf("camminare" to 12335, "correre" to 14990))
            injectField("_lemmaIndex", mapOf("camminare" to 12335))
            injectField("_lemmaPoSIndex", mapOf("camminare|V" to 12335))
            injectField("_ngramIndex", mapOf("buon giorno" to 99001))
        }

        @Test
        @DisplayName("lookupSurface finds exact lower-case word")
        fun lookupSurfaceFound() = assertEquals(12335, lookup.lookupSurface("camminare"))

        @Test
        @DisplayName("lookupSurface is case-insensitive")
        fun lookupSurfaceCaseInsensitive() = assertEquals(12335, lookup.lookupSurface("CAMMINARE"))

        @Test
        @DisplayName("lookupSurface returns null for unknown word")
        fun lookupSurfaceMiss() = assertNull(lookup.lookupSurface("volare"))

        @Test
        @DisplayName("lookupLemma returns id for known lemma")
        fun lookupLemmaFound() = assertEquals(12335, lookup.lookupLemma("camminare"))

        @Test
        @DisplayName("lookupNgram returns id for known phrase")
        fun lookupNgramFound() = assertEquals(99001, lookup.lookupNgram("buon giorno"))

        @Test
        @DisplayName("lookupNgram is case-insensitive")
        fun lookupNgramCaseInsensitive() = assertEquals(99001, lookup.lookupNgram("Buon Giorno"))

        // ── nameOf / synsetOf ──────────────────────────────────────────────────

        @Test
        @DisplayName("nameOf returns English name when present")
        fun nameOfKnown() = assertEquals("walk", lookup.nameOf(12335))

        @Test
        @DisplayName("nameOf returns id.toString() for unknown id")
        fun nameOfUnknown() = assertEquals("99999", lookup.nameOf(99999))

        @Test
        @DisplayName("synsetOf returns Long offset when present")
        fun synsetOfKnown() = assertEquals(202316L, lookup.synsetOf(12335))

        @Test
        @DisplayName("synsetOf returns -1L for unknown id")
        fun synsetOfUnknown() = assertEquals(-1L, lookup.synsetOf(0))

        // ── lookupLemmaPos ────────────────────────────────────────────────────

        @Test
        @DisplayName("lookupLemmaPos returns POS-specific id when key matches")
        fun lookupLemmaPosHit() = assertEquals(12335, lookup.lookupLemmaPos("camminare", "V"))

        @Test
        @DisplayName("lookupLemmaPos falls back to plain lemma when POS key misses")
        fun lookupLemmaPosLemmaFallback() = assertEquals(12335, lookup.lookupLemmaPos("camminare", "N"))
    }

    // ── constants ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Constants")
    inner class Constants {

        @Test
        @DisplayName("SUPPORTED_LANGS contains exactly the 8 declared languages")
        fun supportedLangsSet() {
            val expected = setOf("it", "en", "de", "fr", "es", "nl", "pl", "pt")
            assertEquals(expected, BlissLookup.SUPPORTED_LANGS)
        }

        @Test
        @DisplayName("getInstance() returns the same singleton instance")
        fun singletonIdentity() {
            val a = BlissLookup.getInstance(fakeContext)
            val b = BlissLookup.getInstance(fakeContext)
            assertSame(a, b)
        }

        @Test
        @DisplayName("LoadException preserves message and cause")
        fun loadExceptionFields() {
            val cause = IOException("asset missing")
            val ex    = BlissLookup.LoadException("Failed to load lang=it", cause)
            assertEquals("Failed to load lang=it", ex.message)
            assertSame(cause, ex.cause)
        }
    }
}

// ── private alias to avoid importing java.io.IOException directly in test helpers
private typealias IOException = java.io.IOException
