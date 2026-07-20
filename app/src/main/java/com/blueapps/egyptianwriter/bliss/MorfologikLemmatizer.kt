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
 * Dictionaries are bundled in `assets/morfologik/{lang}.dict` + `{lang}.info`.
 * On first use they are copied to `filesDir/morfologik/` and cached in-memory
 * behind a per-language [Mutex] to guarantee thread-safe initialisation.
 *
 * ## API
 * - [lemmatize]       — returns only the list of lemma strings (backward-compat).
 * - [analyzeWithTags] — returns [LemmaAnalysis] objects carrying lemma + raw tag
 *                       + pre-computed Bliss indicators via [MorfologikTagMapper].
 * - [isAvailable]     — checks whether the `.dict` asset exists for a language.
 */
class MorfologikLemmatizer(private val context: Context) {

    /**
     * Non-null wrapper around a possibly-null [IStemmer].
     *
     * [cache] must distinguish "not yet attempted" (absent key) from
     * "attempted and unavailable" (present key, null stemmer — e.g. missing
     * or corrupt dictionary asset) so a failed load is not retried on every
     * call. [java.util.concurrent.ConcurrentHashMap] does not permit null
     * values, so the null case is represented by [StemmerSlot.stemmer] being
     * null inside a non-null wrapper instance instead.
     */
    private class StemmerSlot(val stemmer: IStemmer?)

    /**
     * Thread-safe cache of loaded stemmers, one slot per language.
     *
     * ## Fix (enterprise-grade audit, 2026-07-20)
     * Previously backed by a plain `HashMap`, read via an unsynchronized
     * fast-path (`cache.containsKey(lang)` / `cache[lang]`) *outside* the
     * per-language [mutexMap] lock, while writes happened *inside* that lock.
     * Because every language shares the same backing map, loading two
     * different languages concurrently (different mutexes → genuinely
     * parallel execution) raced a writer against a reader/writer on the same
     * non-thread-safe `HashMap` instance — undefined behaviour per the JVM
     * memory model (from silently stale reads causing redundant dictionary
     * reloads, up to internal bucket-array corruption during a concurrent
     * resize). `ConcurrentHashMap` fixes both the visibility guarantee and
     * the structural-corruption risk; the per-language [Mutex] is kept to
     * still serialise the (expensive) load-and-populate step per language.
     */
    private val cache: java.util.concurrent.ConcurrentHashMap<String, StemmerSlot> =
        java.util.concurrent.ConcurrentHashMap(8)

    private val mutexMap = HashMap<String, Mutex>(8).apply {
        DICT_LANGS.forEach { lang -> put(lang, Mutex()) }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the distinct base forms (lemmas) for [word] in [lang].
     * Delegates to [analyzeWithTags] — kept for backward compatibility.
     */
    suspend fun lemmatize(word: String, lang: String): List<String> =
        analyzeWithTags(word, lang).map { it.lemma }.distinct()

    /**
     * Returns a list of [LemmaAnalysis] for [word] in [lang], each carrying:
     * - the canonical base form ([LemmaAnalysis.lemma])
     * - the raw Morfologik tag string ([LemmaAnalysis.rawTag])
     * - the Bliss indicators derived from the tag ([LemmaAnalysis.blissIndicators])
     *
     * Returns an empty list if the language is unsupported, the dictionary is
     * unavailable, or an exception occurs (graceful degradation).
     */
    suspend fun analyzeWithTags(word: String, lang: String): List<LemmaAnalysis> {
        val l = lang.lowercase(Locale.ROOT).take(2)
        if (l !in DICT_LANGS) return emptyList()
        val stemmer = getStemmer(l) ?: return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                @Suppress("UNCHECKED_CAST")
                (stemmer as DictionaryLookup)
                    .lookup(word.lowercase(Locale.ROOT))
                    .mapNotNull { result ->
                        val lemma = result.getStem()?.toString()?.trim().orEmpty()
                        if (lemma.isBlank()) return@mapNotNull null
                        val rawTag = result.getTag()?.toString()?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        LemmaAnalysis(
                            lemma          = lemma,
                            rawTag         = rawTag,
                            blissIndicators = MorfologikTagMapper.toBlissIndicators(rawTag)
                        )
                    }
                    .distinctBy { "${it.lemma}|${it.rawTag}" }
            } catch (e: Exception) {
                Log.w(TAG, "analyzeWithTags error for '$word' [$l]", e)
                emptyList()
            }
        }
    }

    /**
     * Returns true if a Morfologik `.dict` asset exists for [lang].
     * Does **not** load or initialise the dictionary.
     */
    fun isAvailable(lang: String): Boolean {
        val l = lang.lowercase(Locale.ROOT).take(2)
        if (l !in DICT_LANGS) return false
        return try {
            context.assets.open("morfologik/$l.dict").close()
            true
        } catch (_: IOException) { false }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private suspend fun getStemmer(lang: String): IStemmer? {
        // Fast path: safe now that `cache` is a ConcurrentHashMap — a slot
        // observed here either doesn't exist yet, or was fully constructed
        // and published by a `put()` that happens-before this `get()`.
        cache[lang]?.let { return it.stemmer }
        val mutex = mutexMap[lang] ?: return null
        return mutex.withLock {
            // Re-check inside the lock: another coroutine may have populated
            // the slot for this language while we were waiting on the mutex.
            cache[lang]?.let { return@withLock it.stemmer }
            val stemmer = withContext(Dispatchers.IO) { loadDictionary(lang) }
            cache[lang] = StemmerSlot(stemmer)
            stemmer
        }
    }

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
        val DICT_LANGS: Set<String> = setOf("it", "en", "de", "fr", "es", "nl", "pl", "pt")
    }
}
