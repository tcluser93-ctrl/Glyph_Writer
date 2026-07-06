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

    private val cache    = HashMap<String, IStemmer?>(8)
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
        if (cache.containsKey(lang)) return cache[lang]
        val mutex = mutexMap[lang] ?: return null
        return mutex.withLock {
            if (cache.containsKey(lang)) return@withLock cache[lang]
            val stemmer = withContext(Dispatchers.IO) { loadDictionary(lang) }
            cache[lang] = stemmer
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
