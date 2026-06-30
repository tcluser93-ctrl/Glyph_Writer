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
 * ## Lookup tier order
 * 1. **N-gram exact match** (HashMap `_ngramIndex`)           — fastest
 * 2. **Lexicon surface match** (HashMap `_lexicon`)           — fast
 * 3. **Lemma match** (HashMap `_lemmaIndex` / `_lemmaPoSIndex`) — fast
 * 4. **Room FTS4 exact match** (`_db.bciDao().lookupExact`)   — ~1 ms, suspend
 * 5. **Room FTS4 prefix match** (`_db.bciDao().lookupPrefix`) — ~2 ms, suspend
 *    (used by UI typeahead; not part of translate() hot path)
 *
 * Tiers 1-3 are synchronous HashMap reads.  Tier 4-5 are suspend functions
 * available to callers via [lookupSurfaceDb] / [lookupPrefixDb].
 *
 * ## Thread-safety
 * Every backing field is `@Volatile`.  Each field is written exactly once
 * from a single background coroutine, so the JVM memory model guarantees
 * that any subsequent read on any thread sees the fully constructed map.
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
 * ## FTS4 dictionary asset (optional, shipped separately)
 * `assets/morfologik/{lang}.dict` + `{lang}.info` — Morfologik FSA binaries.
 * If absent, [MorfologikLemmatizer] degrades gracefully (tier 4 disabled).
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
     */
    val lemmaPoSIndex: Map<String, Int> get() = _lemmaPoSIndex
    /** N-gram phrase → BCI-AV ID (language-specific, lower-cased). */
    val ngramIndex:    Map<String, Int> get() = _ngramIndex

    /** ISO-639-1 code of the last successfully loaded language. */
    @Volatile var currentLang: String? = null
        private set

    /** `true` once [load] completes without error. */
    @Volatile var isReady: Boolean = false
        private set

    // ── private backing fields ───────────────────────────────────────────────

    @Volatile private var _names         = emptyMap<Int, String>()
    @Volatile private var _synsets       = emptyMap<Int, Long>()
    @Volatile private var _lexicon       = emptyMap<String, Int>()
    @Volatile private var _lemmaIndex    = emptyMap<String, Int>()
    @Volatile private var _lemmaPoSIndex = emptyMap<String, Int>()
    @Volatile private var _ngramIndex    = emptyMap<String, Int>()

    /** Room FTS4 database instance (lazy-initialised after first load). */
    @Volatile private var _db: BlissDatabase? = null

    // ── custom exception ─────────────────────────────────────────────────────

    class LoadException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Idempotent load.  No-op when [lang] equals [currentLang] **and** [isReady].
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
                "lemmas=${_lemmaIndex.size}, ngrams=${_ngramIndex.size}")
    }

    /**
     * Initialises the Room FTS4 database and populates it from the current
     * in-memory maps if empty.  Call this from a coroutine **after** [load]
     * has completed.
     *
     * Safe to call multiple times — [BlissDatabase.populateIfEmpty] is idempotent.
     */
    suspend fun initDb() {
        val lang = currentLang ?: return
        val db = BlissDatabase.getInstance(context)
        _db = db
        BlissDatabase.populateIfEmpty(db, _lexicon, _lemmaIndex, lang)
    }

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

    // ── HashMap lookup helpers (sync, tiers 1-3) ─────────────────────────────

    fun nameOf(id: Int): String = _names[id] ?: id.toString()
    fun synsetOf(id: Int): Long = _synsets[id] ?: -1L

    fun lookupSurface(word: String): Int? = _lexicon[word.lowercase(Locale.ROOT)]
    fun lookupLemma(lemma: String): Int?  = _lemmaIndex[lemma.lowercase(Locale.ROOT)]

    fun lookupLemmaPos(lemma: String, pos: String): Int? {
        val key = "${lemma.lowercase(Locale.ROOT)}|${pos.uppercase(Locale.ROOT)}"
        return _lemmaPoSIndex[key] ?: _lemmaIndex[lemma.lowercase(Locale.ROOT)]
    }

    fun lookupNgram(phrase: String): Int? = _ngramIndex[phrase.lowercase(Locale.ROOT)]

    // ── Room FTS4 lookup helpers (suspend, tiers 4-5) ────────────────────────

    /**
     * Exact keyword lookup via Room FTS4.
     * Returns `null` if the DB is not yet initialised or the word is absent.
     * Runs on [Dispatchers.IO] internally (Room suspend functions are
     * already dispatcher-safe).
     *
     * Call this as **tier 4** after all HashMap tiers have missed.
     */
    suspend fun lookupSurfaceDb(word: String): Int? {
        val lang = currentLang ?: return null
        return _db?.bciDao()?.lookupExact(word.lowercase(Locale.ROOT), lang)
    }

    /**
     * Prefix search via Room FTS4 — enables typeahead in the CAA symbol picker.
     * Returns up to [limit] BCI IDs whose keyword starts with [prefix].
     *
     * This is **not** part of the translate() hot path; use it only from UI
     * layer coroutines (e.g. a SearchView.OnQueryTextListener).
     */
    suspend fun lookupPrefixDb(prefix: String, limit: Int = 10): List<Int> {
        val lang = currentLang ?: return emptyList()
        if (prefix.length < 2) return emptyList()  // avoid full-table scan
        return _db?.bciDao()?.lookupPrefix(prefix.lowercase(Locale.ROOT), lang, limit)
            ?: emptyList()
    }

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
                map[id] = json.optString(key, "").takeIf { it.isNotEmpty() } ?: continue
            }
        } ?: Log.w(TAG, "bci_names.json not found")
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
        } ?: Log.w(TAG, "bci_blissnet.json not found")
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
        } ?: Log.w(TAG, "bci_lexicon_$lang.json not found")
        return map
    }

    private fun loadLemmas(lang: String): Pair<Map<String, Int>, Map<String, Int>> {
        val plain = HashMap<String, Int>(12000)
        val pos   = HashMap<String, Int>(12000)
        try {
            context.assets.open("bliss/lemmas_$lang.csv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    .lineSequence().drop(1)
                    .forEach { line ->
                        val parts = line.split(",", limit = 3)
                        if (parts.size < 3) return@forEach
                        val lemma  = parts[0].trim().lowercase(Locale.ROOT)
                        val posTag = parts[1].trim().uppercase(Locale.ROOT)
                        val id     = parts[2].trim().toIntOrNull() ?: return@forEach
                        if (lemma.isEmpty() || id <= 0) return@forEach
                        plain.putIfAbsent(lemma, id)
                        pos["$lemma|$posTag"] = id
                    }
            }
        } catch (io: IOException) {
            Log.w(TAG, "lemmas_$lang.csv not found")
        }
        return plain to pos
    }

    private fun loadNgrams(lang: String): Map<String, Int> {
        val map = HashMap<String, Int>(3000)
        try {
            context.assets.open("bliss/ngrams_multilang.csv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    .lineSequence().drop(1)
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
            Log.w(TAG, "ngrams_multilang.csv not found")
        }
        return map
    }

    private fun readJsonObjectOrNull(assetPath: String): JSONObject? =
        try {
            context.assets.open(assetPath).use { stream ->
                JSONObject(stream.bufferedReader(Charsets.UTF_8).readText())
            }
        } catch (_: IOException) { null }
          catch (e: org.json.JSONException) {
              Log.e(TAG, "Malformed JSON in $assetPath", e); null
          }

    private fun normaliseLang(code: String): String {
        val lc = code.lowercase(Locale.ROOT).take(2)
        return if (lc in SUPPORTED_LANGS) lc else DEFAULT_LANG
    }

    companion object {
        private const val TAG          = "BlissLookup"
        private const val DEFAULT_LANG = "it"

        val SUPPORTED_LANGS: Set<String> = setOf(
            "it", "en", "de", "fr", "es", "nl", "pl", "pt"
        )

        @Volatile private var INSTANCE: BlissLookup? = null

        fun getInstance(context: Context): BlissLookup =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlissLookup(context.applicationContext).also { INSTANCE = it }
            }
    }
}
