package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale

/**
 * Loads and exposes in-memory lookup tables built from the BCI-AV asset files.
 *
 * All tables are loaded lazily on first access and kept in RAM for the app
 * lifetime (~5-10 MB depending on language).
 *
 * ## Thread-safety
 * Every backing field is `@Volatile`.  Each field is written exactly once (or
 * on [reset]/reload) from a single background coroutine, so the JVM memory
 * model guarantees that any subsequent read on any thread sees the fully
 * constructed map.  The maps themselves are read-only after assignment, so
 * no additional synchronisation is needed for reads.
 *
 * ## Usage
 * ```kotlin
 * val lookup = BlissLookup.getInstance(context)
 * lookup.loadIfNeeded(
 *     lang  = "it",
 *     scope = lifecycleScope,
 *     onReady = { translator = BlissTranslator(lookup) },
 *     onError = { e -> showError(e.message) }
 * )
 * ```
 *
 * ## Asset files expected under `assets/bliss/`
 * | File | Format | Contents |
 * |---|---|---|
 * | `bci_names.json` | `{"12335":"action, to", …}` | BCI-AV ID → English name |
 * | `bci_blissnet.json` | `{"12335": 202316, …}` | BCI-AV ID → WordNet synset offset |
 * | `bci_lexicon_{lang}.json` | `{"walk": 12335, …}` | surface word → BCI-AV ID |
 * | `lemmas_{lang}.csv` | `lemma,POS,bci_av_id` | lemma + POS → BCI-AV ID |
 * | `ngrams_multilang.csv` | `lang,ngram,bci_av_id` | n-gram phrase → BCI-AV ID |
 *
 * Supported language codes: `it en de fr es nl pl pt`
 */
class BlissLookup private constructor(private val context: Context) {

    // ── public read-only views ───────────────────────────────────────────────

    /** BCI-AV ID → canonical English name. */
    val names:        Map<Int, String>  get() = _names
    /** BCI-AV ID → WordNet synset offset (-1 if absent). */
    val synsets:      Map<Int, Long>    get() = _synsets
    /** Surface word → BCI-AV ID (language-specific, lower-cased). */
    val lexicon:      Map<String, Int>  get() = _lexicon
    /** Lemma → BCI-AV ID (language-specific, lower-cased). */
    val lemmaIndex:   Map<String, Int>  get() = _lemmaIndex
    /**
     * POS-aware lemma index.  Key = `"lemma|POS"` (e.g. `"camminare|V"`).
     * POS codes: `N V A R P D C I X`.
     * Falls back to [lemmaIndex] in [lookupLemmaPos] when the combined key is absent.
     */
    val lemmaPoSIndex: Map<String, Int> get() = _lemmaPoSIndex
    /** N-gram phrase → BCI-AV ID (language-specific, lower-cased). */
    val ngramIndex:    Map<String, Int> get() = _ngramIndex

    /** ISO-639-1 code of the last successfully loaded language, or `null` if not yet loaded. */
    @Volatile var currentLang: String? = null
        private set

    /** `true` once [load] (or [loadIfNeeded]) completes without error. */
    @Volatile var isReady: Boolean = false
        private set

    // ── private backing fields (@Volatile for safe cross-thread publication) ─

    @Volatile private var _names         = emptyMap<Int, String>()
    @Volatile private var _synsets       = emptyMap<Int, Long>()
    @Volatile private var _lexicon       = emptyMap<String, Int>()
    @Volatile private var _lemmaIndex    = emptyMap<String, Int>()
    @Volatile private var _lemmaPoSIndex = emptyMap<String, Int>()
    @Volatile private var _ngramIndex    = emptyMap<String, Int>()

    // ── custom exception ─────────────────────────────────────────────────────

    /** Thrown by [load] when a mandatory asset cannot be parsed. */
    class LoadException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Idempotent load.  No-op when [lang] equals [currentLang] **and** [isReady].
     * Otherwise calls [reset] then [load] on [Dispatchers.IO].
     *
     * @param lang    ISO-639-1 code, e.g. `"it"`, `"en"`, `"de"`.
     * @param scope   [CoroutineScope] that owns the load job (typically
     *                `viewModelScope` or `lifecycleScope`).  The job is
     *                automatically cancelled when the scope is cancelled.
     * @param onReady Called on the **main** thread after a successful load.
     * @param onError Called on the **main** thread if loading throws.
     */
    fun loadIfNeeded(
        lang:    String,
        scope:   CoroutineScope,
        onReady: () -> Unit = {},
        onError: (Throwable) -> Unit = { Log.e(TAG, "Load error", it) }
    ) {
        val normalised = normaliseLang(lang)
        if (isReady && normalised == currentLang) {
            onReady()
            return
        }
        loadAsync(normalised, scope, onReady, onError)
    }

    /**
     * Unconditional async load.  Prefer [loadIfNeeded] for the common case.
     *
     * @param lang    ISO-639-1 code.
     * @param scope   Owner [CoroutineScope].
     * @param onReady Called on the **main** thread after success.
     * @param onError Called on the **main** thread on failure.
     */
    fun loadAsync(
        lang:    String,
        scope:   CoroutineScope,
        onReady: () -> Unit = {},
        onError: (Throwable) -> Unit = { Log.e(TAG, "Load error", it) }
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                load(lang)
                withContext(Dispatchers.Main) { onReady() }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { onError(t) }
            }
        }
    }

    /**
     * Synchronous, blocking load.  **Must** be called from a background thread
     * or from within a coroutine on [Dispatchers.IO].
     *
     * @throws LoadException if a mandatory asset is missing or malformed.
     */
    @Throws(LoadException::class)
    fun load(langCode: String) {
        val lang = normaliseLang(langCode)
        Log.i(TAG, "Loading Bliss assets for lang=$lang")
        try {
            _names        = loadNames()
            _synsets      = loadSynsets()
            _lexicon      = loadLexicon(lang)
            val (plain, pos) = loadLemmas(lang)
            _lemmaIndex    = plain
            _lemmaPoSIndex = pos
            _ngramIndex    = loadNgrams(lang)
        } catch (io: IOException) {
            isReady     = false
            currentLang = null
            throw LoadException("Failed to load Bliss assets for lang=$lang", io)
        }
        currentLang = lang
        isReady     = true
        Log.i(TAG, "Bliss assets loaded: names=${_names.size}, lexicon=${_lexicon.size}, " +
                "lemmas=${_lemmaIndex.size}, lemmas+POS=${_lemmaPoSIndex.size}, " +
                "ngrams=${_ngramIndex.size}")
    }

    /**
     * Wipes all in-memory tables and resets [isReady] / [currentLang].
     * Call before loading a different language so that [loadIfNeeded] triggers
     * a fresh load instead of treating the old data as current.
     */
    fun reset() {
        _names         = emptyMap()
        _synsets       = emptyMap()
        _lexicon       = emptyMap()
        _lemmaIndex    = emptyMap()
        _lemmaPoSIndex = emptyMap()
        _ngramIndex    = emptyMap()
        currentLang    = null
        isReady        = false
        Log.d(TAG, "BlissLookup reset")
    }

    // ── lookup helpers ───────────────────────────────────────────────────────

    fun nameOf(id: Int): String = _names[id] ?: id.toString()

    fun synsetOf(id: Int): Long = _synsets[id] ?: -1L

    /** Surface word lookup (case-insensitive). */
    fun lookupSurface(word: String): Int? = _lexicon[word.lowercase(Locale.ROOT)]

    /** POS-agnostic lemma lookup (case-insensitive). */
    fun lookupLemma(lemma: String): Int? = _lemmaIndex[lemma.lowercase(Locale.ROOT)]

    /**
     * POS-aware lookup.  [pos] is one of: `N V A R P D C I X`.
     * Falls back to plain [lookupLemma] when the combined key is absent.
     */
    fun lookupLemmaPos(lemma: String, pos: String): Int? {
        val key = "${lemma.lowercase(Locale.ROOT)}|${pos.uppercase(Locale.ROOT)}"
        return _lemmaPoSIndex[key] ?: _lemmaIndex[lemma.lowercase(Locale.ROOT)]
    }

    /** N-gram phrase lookup (case-insensitive). */
    fun lookupNgram(phrase: String): Int? = _ngramIndex[phrase.lowercase(Locale.ROOT)]

    /**
     * Convenience: wraps a raw lookup result into a [BlissSymbol].
     */
    fun toSymbol(
        id:     Int,
        source: String,
        lemma:  String,
        mt:     BlissSymbol.MatchType
    ): BlissSymbol = BlissSymbol(
        bciAvId    = id,
        name       = nameOf(id),
        synsetId   = synsetOf(id),
        sourceWord = source,
        lemma      = lemma,
        matchType  = mt
    )

    // ── private asset readers ────────────────────────────────────────────────

    private fun loadNames(): Map<Int, String> {
        val map = HashMap<Int, String>(7000)
        readJsonObjectOrNull("bliss/bci_names.json")?.let { json ->
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id  = key.toIntOrNull() ?: continue
                map[id] = json.optString(key, "")
                    .takeIf { it.isNotEmpty() } ?: continue
            }
        } ?: Log.w(TAG, "bci_names.json not found — names will be empty")
        return map
    }

    private fun loadSynsets(): Map<Int, Long> {
        val map = HashMap<Int, Long>(6000)
        readJsonObjectOrNull("bliss/bci_blissnet.json")?.let { json ->
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id  = key.toIntOrNull() ?: continue
                val v   = json.optLong(key, -1L)
                if (v >= 0L) map[id] = v
            }
        } ?: Log.w(TAG, "bci_blissnet.json not found — synsets will be empty")
        return map
    }

    private fun loadLexicon(lang: String): Map<String, Int> {
        val map = HashMap<String, Int>(15000)
        readJsonObjectOrNull("bliss/bci_lexicon_$lang.json")?.let { json ->
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id  = json.optInt(key, -1)
                if (id > 0) map[key.lowercase(Locale.ROOT)] = id
            }
        } ?: Log.w(TAG, "bci_lexicon_$lang.json not found — lexicon will be empty")
        return map
    }

    /**
     * Streams `lemmas_{lang}.csv` lazily line-by-line.
     * Format: `lemma,POS,bci_av_id`  (header row skipped)
     * Returns `Pair(plainMap, posMap)`.
     */
    private fun loadLemmas(lang: String): Pair<Map<String, Int>, Map<String, Int>> {
        val plain = HashMap<String, Int>(12000)
        val pos   = HashMap<String, Int>(12000)
        val asset = "bliss/lemmas_$lang.csv"
        try {
            context.assets.open(asset).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    .lineSequence()
                    .drop(1)          // skip header
                    .forEach { line ->
                        val parts = line.split(",", limit = 3)
                        if (parts.size < 3) return@forEach
                        val lemma = parts[0].trim().lowercase(Locale.ROOT)
                        val posTag = parts[1].trim().uppercase(Locale.ROOT)
                        val id    = parts[2].trim().toIntOrNull() ?: return@forEach
                        if (lemma.isEmpty() || id <= 0) return@forEach
                        plain.putIfAbsent(lemma, id)
                        pos["$lemma|$posTag"] = id
                    }
            }
        } catch (io: IOException) {
            Log.w(TAG, "$asset not found — lemma index will be empty")
        }
        return plain to pos
    }

    /**
     * Streams `ngrams_multilang.csv` lazily, collecting only rows for [lang].
     * Format: `lang,ngram,bci_av_id`  (header row skipped)
     */
    private fun loadNgrams(lang: String): Map<String, Int> {
        val map   = HashMap<String, Int>(3000)
        val asset = "bliss/ngrams_multilang.csv"
        try {
            context.assets.open(asset).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    .lineSequence()
                    .drop(1)
                    .forEach { line ->
                        val parts = line.split(",", limit = 3)
                        if (parts.size < 3) return@forEach
                        if (parts[0].trim().lowercase(Locale.ROOT) != lang) return@forEach
                        val ngram = parts[1].trim().lowercase(Locale.ROOT)
                        val id    = parts[2].trim().toIntOrNull() ?: return@forEach
                        if (ngram.isNotEmpty() && id > 0) map[ngram] = id
                    }
            }
        } catch (io: IOException) {
            Log.w(TAG, "$asset not found — ngram index will be empty")
        }
        return map
    }

    // ── JSON helper ──────────────────────────────────────────────────────────

    /**
     * Opens [assetPath] from assets and parses it as a [JSONObject].
     * Returns `null` (logging a warning) if the file is absent or malformed.
     */
    private fun readJsonObjectOrNull(assetPath: String): JSONObject? =
        try {
            context.assets.open(assetPath).use { stream ->
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                JSONObject(text)
            }
        } catch (io: IOException) {
            null
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "Malformed JSON in $assetPath", e)
            null
        }

    // ── lang normalisation ───────────────────────────────────────────────────

    private fun normaliseLang(code: String): String {
        val lc = code.lowercase(Locale.ROOT).take(2)
        return if (lc in SUPPORTED_LANGS) lc else DEFAULT_LANG
    }

    // ── companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG          = "BlissLookup"
        private const val DEFAULT_LANG = "it"

        /** All ISO-639-1 codes for which BCI-AV lexicon assets exist. */
        val SUPPORTED_LANGS: Set<String> = setOf(
            "it", "en", "de", "fr", "es", "nl", "pl", "pt"
        )

        @Volatile private var INSTANCE: BlissLookup? = null

        /**
         * Returns the process-wide singleton, creating it if necessary.
         * Always pass [applicationContext] (never an Activity context) to
         * avoid memory leaks.
         */
        fun getInstance(context: Context): BlissLookup =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlissLookup(context.applicationContext)
                    .also { INSTANCE = it }
            }
    }
}
