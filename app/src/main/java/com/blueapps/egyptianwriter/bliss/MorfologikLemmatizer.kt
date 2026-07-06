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
 *   it.dict / it.info   — Italian    (~3.8 MB, LGPL)
 *   en.dict / en.info   — English    (~4.1 MB, BSD)
 *   de.dict / de.info   — German     (~5.2 MB, LGPL)
 *   fr.dict / fr.info   — French     (~3.5 MB, LGPL)
 *   es.dict / es.info   — Spanish    (~4.0 MB, LGPL)
 *   nl.dict / nl.info   — Dutch      (~2.8 MB, LGPL)
 *   pl.dict / pl.info   — Polish     (~3.1 MB, LGPL)
 *   pt.dict / pt.info   — Portuguese (~3.3 MB, LGPL)
 * ```
 *
 * Dictionaries sourced from LanguageTool JARs:
 *   https://github.com/languagetool-org/languagetool/releases
 * Extract with: jar xf languagetool-language-modules-{lang}-*.jar
 * Path inside JAR: org/languagetool/resource/{lang}/{lang}.dict + {lang}.info
 *
 * ## Architecture
 * This lemmatizer is the PRIMARY morphological layer in [BlissTranslator].
 * It runs BEFORE the lemma CSV lookup, providing the canonical lemma from
 * any inflected form. The CSV contains only base lemmas mapped to BCI-AV IDs.
 *
 * Pipeline: surface word → Morfologik FSA → lemma → CSV HashMap → BCI-AV ID
 *
 * ## Thread-safety
 * Each language has its own [Mutex]. The first call for a language copies the
 * asset to [Context.filesDir], opens the [DictionaryLookup] once, and caches
 * it. Subsequent calls are lock-free reads on the cached [IStemmer].
 *
 * ## Usage
 * ```kotlin
 * val lemmatizer = MorfologikLemmatizer(context)
 * val lemmas = lemmatizer.lemmatize("walking", "en")    // ["walk"]
 * val lemmas = lemmatizer.lemmatize("camminando", "it") // ["camminare"]
 * val lemmas = lemmatizer.lemmatize("mangeait", "fr")   // ["manger"]
 * val lemmas = lemmatizer.lemmatize("dormían", "es")    // ["dormir"]
 * ```
 *
 * @param context Application context (used for [Context.filesDir] and assets).
 */
class MorfologikLemmatizer(private val context: Context) {

    // ── per-language stemmer cache ─────────────────────────────────────────
    private val cache    = HashMap<String, IStemmer?>(8)
    private val mutexMap = HashMap<String, Mutex>(8).apply {
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
     * @param lang  ISO-639-1 code, e.g. "it", "en", "de", "fr", "es", "nl".
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
        if (cache.containsKey(lang)) return cache[lang]
        val mutex = mutexMap[lang] ?: return null
        return mutex.withLock {
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
            ensureExtracted(lang, "info", dir)
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
         * Languages for which FSA dictionaries are expected in assets/morfologik/.
         * All 8 languages are shipped in the APK (no on-demand delivery).
         * Dict files sourced from LanguageTool JARs — see class KDoc for details.
         */
        val DICT_LANGS: Set<String> = setOf("it", "en", "de", "fr", "es", "nl", "pl", "pt")
    }
}
