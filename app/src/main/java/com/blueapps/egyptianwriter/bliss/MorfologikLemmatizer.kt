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
 * ## Architecture
 * This is the **primary lemmatization layer** in the BlissTranslator pipeline.
 * Given a surface form ("mangeait", "geschrieben", "dormivano"), it returns the
 * canonical lemma(s) used to look up BCI-AV symbol IDs in the CSV index.
 *
 * Pipeline position:
 *   surface → [MorfologikLemmatizer] → lemma → CSV HashMap → BCI-AV ID
 *
 * ## Asset layout
 * ```
 * assets/morfologik/
 *   it.dict / it.info   — Italian   FSA  (~3.8 MB, LanguageTool LGPL)
 *   en.dict / en.info   — English   FSA  (~4.1 MB, LanguageTool BSD)
 *   de.dict / de.info   — German    FSA  (~5.2 MB, LanguageTool LGPL)
 *   fr.dict / fr.info   — French    FSA  (~3.5 MB, LanguageTool LGPL)
 *   es.dict / es.info   — Spanish   FSA  (~4.8 MB, LanguageTool LGPL)
 *   nl.dict / nl.info   — Dutch     FSA  (~2.9 MB, LanguageTool LGPL)
 *   pt.dict / pt.info   — Portuguese FSA (~3.2 MB, LanguageTool LGPL)
 *   pl.dict / pl.info   — Polish    FSA  (~6.1 MB, Morfologik native)
 * ```
 *
 * Source: extract from LanguageTool JARs:
 *   jar xf languagetool-language-modules-{lang}.jar
 *   → org/languagetool/resource/{lang}/{lang}.dict
 *   → org/languagetool/resource/{lang}/{lang}.info
 *
 * ## Thread-safety
 * Each language has its own [Mutex]. The first call for a language copies the
 * asset to [Context.filesDir]/morfologik/, opens the [DictionaryLookup] once,
 * and caches it. Subsequent calls skip the lock entirely (fast path).
 *
 * ## Graceful degradation
 * If a .dict asset is absent, [lemmatize] returns an empty list — the
 * BlissTranslator pipeline continues with rule-based de-affixation (tier 3d).
 *
 * ## Usage
 * ```kotlin
 * val lemmatizer = MorfologikLemmatizer(context)
 * val lemmas = lemmatizer.lemmatize("walking", "en")      // → ["walk"]
 * val lemmas = lemmatizer.lemmatize("camminando", "it")   // → ["camminare"]
 * val lemmas = lemmatizer.lemmatize("geschrieben", "de")  // → ["schreiben"]
 * val lemmas = lemmatizer.lemmatize("mangeait", "fr")     // → ["manger"]
 * val lemmas = lemmatizer.lemmatize("dormivano", "es")    // → ["dormir"]
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
     * @param lang  ISO-639-1 code, e.g. `"it"`, `"en"`, `"de"`, `"fr"`, `"es"`.
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
         * All supported language codes.
         * Each requires `assets/morfologik/{lang}.dict` + `{lang}.info`.
         *
         * Source: LanguageTool language module JARs (LGPL/BSD).
         * Polish: Morfologik native dictionary.
         *
         * To add a language:
         *   1. Extract {lang}.dict + {lang}.info from the LT JAR
         *   2. Place in app/src/main/assets/morfologik/
         *   3. Add the ISO-639-1 code to this set
         */
        val DICT_LANGS: Set<String> = setOf(
            "it",  // Italian   — LanguageTool LGPL
            "en",  // English   — LanguageTool BSD
            "de",  // German    — LanguageTool LGPL
            "fr",  // French    — LanguageTool LGPL
            "es",  // Spanish   — LanguageTool LGPL
            "nl",  // Dutch     — LanguageTool LGPL
            "pt",  // Portuguese— LanguageTool LGPL
            "pl"   // Polish    — Morfologik native
        )
    }
}
