package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Loads and exposes in-memory lookup tables built from the BCI-AV asset files.
 *
 * All assets are read lazily once on first access and kept in RAM for the app
 * lifetime (~5-10 MB depending on language).  The heavier lexicons are loaded
 * on a background thread via [BlissLookup.loadAsync].
 *
 * Asset files expected under  assets/bliss/ :
 *   bci_names.json          { "12335": "action, to", … }
 *   bci_blissnet.json       { "12335": 202316, … }   (synset offsets)
 *   bci_lexicon_{lang}.json { "walk": 12335, … }     (surface → BCI-AV)
 *   lemmas_{lang}.csv       lemma,POS,bci_av_id       (lemma → BCI-AV)
 *   ngrams_multilang.csv    lang,ngram,bci_av_id
 *
 * Supported language codes: it, en, de, fr, es, nl, pl, pt
 */
class BlissLookup private constructor(private val context: Context) {

    // ── public tables (populated by load()) ─────────────────────────────────

    /** BCI-AV ID → canonical English name */
    val names: Map<Int, String> get() = _names

    /** BCI-AV ID → WordNet synset offset (-1 if absent) */
    val synsets: Map<Int, Long> get() = _synsets

    /** surface word → BCI-AV ID  (language-specific) */
    val lexicon: Map<String, Int> get() = _lexicon

    /** lemma → BCI-AV ID  (language-specific, plain key = lemma lowercase) */
    val lemmaIndex: Map<String, Int> get() = _lemmaIndex

    /**
     * POS-aware lemma index: key = "lemma|POS" (e.g. "camminare|V").
     * Falls back to plain [lemmaIndex] if POS is unknown.
     * POS values in CSV: N=noun, V=verb, A=adjective, R=adverb, P=pronoun,
     * D=determiner, C=conjunction, I=interjection, X=other.
     */
    val lemmaPoSIndex: Map<String, Int> get() = _lemmaPoSIndex

    /** language-specific n-gram phrases → BCI-AV ID */
    val ngramIndex: Map<String, Int> get() = _ngramIndex

    /** true once [load] (or [loadAsync]) has completed without errors */
    @Volatile var isReady: Boolean = false
        private set

    // ── private mutable backing fields ──────────────────────────────────────

    private var _names        = emptyMap<Int, String>()
    private var _synsets      = emptyMap<Int, Long>()
    private var _lexicon      = emptyMap<String, Int>()
    private var _lemmaIndex   = emptyMap<String, Int>()
    private var _lemmaPoSIndex= emptyMap<String, Int>()
    private var _ngramIndex   = emptyMap<String, Int>()

    // ── loading ──────────────────────────────────────────────────────────────

    /**
     * Synchronous load — call from a background thread or during app start.
     * @param langCode  ISO-639-1 code, e.g. "it", "en", "de"
     */
    fun load(langCode: String) {
        val lang = normaliseLang(langCode)
        Log.i(TAG, "Loading Bliss assets for lang=$lang")

        _names       = loadNames()
        _synsets     = loadSynsets()
        _lexicon     = loadLexicon(lang)
        val (plain, pos) = loadLemmas(lang)
        _lemmaIndex   = plain
        _lemmaPoSIndex= pos
        _ngramIndex  = loadNgrams(lang)

        isReady = true
        Log.i(TAG, "Bliss assets loaded: names=${_names.size}, lexicon=${_lexicon.size}, " +
                "lemmas=${_lemmaIndex.size}, lemmas+POS=${_lemmaPoSIndex.size}, " +
                "ngrams=${_ngramIndex.size}")
    }

    /**
     * Asynchronous load — fires [onReady] on the calling thread's Looper.
     * Safe to call from the main thread.
     */
    fun loadAsync(
        langCode: String,
        onReady: () -> Unit = {},
        onError: (Throwable) -> Unit = { Log.e(TAG, "Load error", it) }
    ) {
        Thread {
            try {
                load(langCode)
                android.os.Handler(android.os.Looper.getMainLooper()).post(onReady)
            } catch (t: Throwable) {
                onError(t)
            }
        }.apply { name = "BlissLookup-load"; isDaemon = true; start() }
    }

    // ── lookup helpers ────────────────────────────────────────────────────────

    fun nameOf(id: Int): String = _names[id] ?: id.toString()

    fun synsetOf(id: Int): Long = _synsets[id] ?: -1L

    fun lookupSurface(word: String): Int? = _lexicon[word.lowercase(Locale.ROOT)]

    /** Plain lemma lookup (POS-agnostic). */
    fun lookupLemma(lemma: String): Int? = _lemmaIndex[lemma.lowercase(Locale.ROOT)]

    /**
     * POS-aware lemma lookup. [pos] is one of: N V A R P D C I X.
     * Falls back to plain [lookupLemma] when POS key is absent.
     */
    fun lookupLemmaPos(lemma: String, pos: String): Int? {
        val key = "${lemma.lowercase(Locale.ROOT)}|${pos.uppercase(Locale.ROOT)}"
        return _lemmaPoSIndex[key] ?: _lemmaIndex[lemma.lowercase(Locale.ROOT)]
    }

    fun lookupNgram(phrase: String): Int? = _ngramIndex[phrase.lowercase(Locale.ROOT)]

    fun toSymbol(id: Int, source: String, lemma: String, mt: BlissSymbol.MatchType): BlissSymbol =
        BlissSymbol(
            bciAvId    = id,
            name       = nameOf(id),
            synsetId   = synsetOf(id),
            sourceWord = source,
            lemma      = lemma,
            matchType  = mt
        )

    // ── private asset readers ─────────────────────────────────────────────────

    private fun loadNames(): Map<Int, String> {
        val map = HashMap<Int, String>(6500)
        readJsonObjectOrNull("bliss/bci_names.json")?.run {
            keys().forEach { key ->
                map[key.toIntOrNull() ?: return@forEach] = getString(key)
            }
        } ?: Log.w(TAG, "bci_names.json not found — names will be empty")
        return map
    }

    private fun loadSynsets(): Map<Int, Long> {
        val map = HashMap<Int, Long>(5500)
        readJsonObjectOrNull("bliss/bci_blissnet.json")?.run {
            keys().forEach { key ->
                val id = key.toIntOrNull() ?: return@forEach
                map[id] = getLong(key)
            }
        } ?: Log.w(TAG, "bci_blissnet.json not found — synsets will be empty")
        return map
    }

    private fun loadLexicon(lang: String): Map<String, Int> {
        val map = HashMap<String, Int>(14000)
        readJsonObjectOrNull("bliss/bci_lexicon_$lang.json")?.run {
            keys().forEach { key ->
                map[key.lowercase(Locale.ROOT)] = getInt(key)
            }
        } ?: Log.w(TAG, "bci_lexicon_$lang.json not found — lexicon will be empty")
        return map
    }

    /**
     * Returns a pair: (plain lemma→id map, "lemma|POS"→id map).
     * CSV format: lemma,POS,bci_av_id  (header line starting with '#' is skipped)
     */
    private fun loadLemmas(lang: String): Pair<Map<String, Int>, Map<String, Int>> {
        val plain = HashMap<String, Int>(14000)
        val pos   = HashMap<String, Int>(14000)
        val assetPath = "bliss/lemmas_$lang.csv"
        if (!assetExists(assetPath)) {
            Log.w(TAG, "$assetPath not found — lemma index will be empty")
            return plain to pos
        }
        readCsv(assetPath).forEach { cols ->
            if (cols.size >= 3) {
                val lemma  = cols[0].lowercase(Locale.ROOT)
                val posTag = cols[1].uppercase(Locale.ROOT)
                val id     = cols[2].toIntOrNull() ?: return@forEach
                plain[lemma] = id
                pos["$lemma|$posTag"] = id
            }
        }
        return plain to pos
    }

    private fun loadNgrams(lang: String): Map<String, Int> {
        val map = HashMap<String, Int>(600)
        val assetPath = "bliss/ngrams_multilang.csv"
        if (!assetExists(assetPath)) {
            Log.w(TAG, "$assetPath not found — ngram index will be empty")
            return map
        }
        readCsv(assetPath).forEach { cols ->
            if (cols.size >= 3 && cols[0] == lang) {
                val phrase = cols[1].lowercase(Locale.ROOT)
                val id     = cols[2].toIntOrNull() ?: return@forEach
                map[phrase] = id
            }
        }
        return map
    }

    // ── asset I/O ─────────────────────────────────────────────────────────────

    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).close(); true
    } catch (_: Exception) { false }

    private fun readJsonObjectOrNull(assetPath: String): JSONObject? = try {
        val text = context.assets.open(assetPath).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }
        JSONObject(text)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read $assetPath: ${e.message}")
        null
    }

    private fun readCsv(assetPath: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        context.assets.open(assetPath).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).forEachLine { line ->
                if (line.isNotBlank() && !line.startsWith("#"))
                    rows.add(line.split(",").map { it.trim() })
            }
        }
        return rows
    }

    // ── lang normalisation ────────────────────────────────────────────────────

    private fun normaliseLang(code: String): String {
        val base = code.lowercase(Locale.ROOT).take(2)
        return if (base in SUPPORTED_LANGS) base else DEFAULT_LANG
    }

    // ── companion / factory ───────────────────────────────────────────────────

    companion object {
        private const val TAG          = "BlissLookup"
        private const val DEFAULT_LANG = "en"
        val SUPPORTED_LANGS = setOf("it", "en", "de", "fr", "es", "nl", "pl", "pt")

        @Volatile private var INSTANCE: BlissLookup? = null

        /** Application-scoped singleton. */
        fun getInstance(context: Context): BlissLookup =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BlissLookup(context.applicationContext).also { INSTANCE = it }
            }
    }
}
