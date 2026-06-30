package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import morfologik.stemming.Dictionary
import morfologik.stemming.DictionaryLookup
import morfologik.stemming.IStemmer
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Offline morphological lemmatizer backed by Morfologik FSA dictionaries.
 *
 * ## Asset layout
 * ```
 * assets/morfologik/
 *   it.dict   — Italian FSA dictionary   (~3.8 MB, LGPL)
 *   it.info   — Italian dict metadata
 *   en.dict   — English FSA dictionary   (~4.1 MB, BSD)
 *   en.info   — English dict metadata
 *   de.dict   — German FSA dictionary    (~5.2 MB, LGPL)
 *   de.info   — German dict metadata
 * ```
 *
 * Dictionaries are NOT bundled in the repo (binary assets, download separately).
 * See: https://github.com/morfologik/morfologik-stemming/releases
 *
 * ## Thread-safety
 * Each language has its own [Mutex]. The first call for a language copies the
 * asset to [Context.filesDir], opens the [DictionaryLookup] once, and caches
 * it. Subsequent calls are lock-free reads on the cached [IStemmer].
 *
 * ## Usage
 * ```kotlin
 * val lemmatizer = MorfologikLemmatizer(context)
 * val lemmas = lemmatizer.lemmatize("walking", "en")  // ["walk"]
 * val lemmas = lemmatizer.lemmatize("camminando", "it") // ["camminare"]
 * ```
 *
 * @param context Application context (used for [Context.filesDir] and assets).
 */
class MorfologikLemmatizer(private val context: Context) {

    // ── per-language stemmer cache ─────────────────────────────────────────
    private val cache  = HashMap<String, IStemmer?>(4)
    private val mutexMap = HashMap<String, Mutex>(4).apply {
        DICT_LANGS.forEach { lang -> put(lang, Mutex()) }
    }

    /**
     * Returns a list of lemma candidates for [word] in the given [lang].
     *
     * - Returns an empty list for languages not in [DICT_LANGS].
     * - Returns an empty list (OOV) when the word is not in the FSA.
     * - Returns an empty list if the dictionary asset is missing (graceful
     *   degradation — rule-based tier continues to work).
     *
     * **Must** be called from a coroutine; runs on [Dispatchers.IO].
     *
     * @param word  Surface word, any case (will be lowercased internally).
     * @param lang  ISO-639-1 code, e.g. `"it"`, `"en"`, `"de"`.
     */
    suspend fun lemmatize(word: String, lang: String): List<String> {
        val l = lang.lowercase(Locale.ROOT).take(2)
        if (l !in DICT_LANGS) return emptyList()
        val stemmer = getStemmer(l) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                @Suppress("UNCHECKED_CAST")
                (stemmer as DictionaryLookup)
                    .lookup(word.lowercase(Locale.ROOT))
                    .map { it.getStem().toString() }
                    .distinct()
            } catch (e: Exception) {
                Log.w(TAG, "Morfologik lookup error for '$word' [$l]", e)
                emptyList()
            }
        }
    }

    /**
     * Returns `true` if the FSA dictionary asset is present for [lang].
     * Does **not** open or validate the file.
     */
    fun isAvailable(lang: String): Boolean {
        val l = lang.lowercase(Locale.ROOT).take(2)
        if (l !in DICT_LANGS) return false
        return try {
            context.assets.open("morfologik/$l.dict").close()
            true
        } catch (_: IOException) { false }
    }

    // ── internal: lazy dictionary loader ─────────────────────────────────

    private suspend fun getStemmer(lang: String): IStemmer? {
        // Fast path: already cached (even if null = unavailable)
        if (cache.containsKey(lang)) return cache[lang]

        val mutex = mutexMap[lang] ?: return null
        return mutex.withLock {
            // Double-checked inside lock
            if (cache.containsKey(lang)) return@withLock cache[lang]
            val stemmer = withContext(Dispatchers.IO) { loadDictionary(lang) }
            cache[lang] = stemmer
            stemmer
        }
    }

    /**
     * Copies dict + info assets to [Context.filesDir]/morfologik/ on first run,
     * then opens a [DictionaryLookup]. Returns `null` if assets are absent.
     */
    private fun loadDictionary(lang: String): IStemmer? {
        val dir = File(context.filesDir, "morfologik").also { it.mkdirs() }
        return try {
            val dictFile = ensureExtracted(lang, "dict", dir)
            val infoFile = ensureExtracted(lang, "info", dir)
            val dict = Dictionary.read(dictFile.toPath())
            DictionaryLookup(dict).also {
                Log.i(TAG, "Morfologik [$lang] loaded — ${dictFile.length() / 1024} KB")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Morfologik [$lang] dict not found in assets — tier disabled", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Morfologik [$lang] load error", e)
            null
        }
    }

    /**
     * Copies asset `morfologik/{lang}.{ext}` to [dir]/{lang}.{ext} if not
     * already present (idempotent). Returns the [File] path.
     */
    private fun ensureExtracted(lang: String, ext: String, dir: File): File {
        val dest = File(dir, "$lang.$ext")
        if (dest.exists() && dest.length() > 0L) return dest
        context.assets.open("morfologik/$lang.$ext").use { src ->
            dest.outputStream().use { dst -> src.copyTo(dst) }
        }
        return dest
    }

    companion object {
        private const val TAG = "MorfologikLemmatizer"

        /**
         * Languages for which FSA dictionaries are expected.
         * Extend by adding `{lang}.dict` + `{lang}.info` to
         * `assets/morfologik/` and adding the code here.
         */
        val DICT_LANGS: Set<String> = setOf("it", "en", "de")
    }
}
