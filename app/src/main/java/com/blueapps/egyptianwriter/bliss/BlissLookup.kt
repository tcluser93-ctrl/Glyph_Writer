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

    /** lemma → BCI-AV ID  (language-specific) */
    val lemmaIndex: Map<String, Int> get() = _lemmaIndex

    /** language-specific n-gram phrases → BCI-AV ID */
    val ngramIndex: Map<String, Int> get() = _ngramIndex

    /** true once [load] (or [loadAsync]) has completed without errors */
    @Volatile var isReady: Boolean = false
        private set

    // ── private mutable backing fields ──────────────────────────────────────

    private var _names      = emptyMap<Int, String>()
    private var _synsets    = emptyMap<Int, Long>()
    private var _lexicon    = emptyMap<String, Int>()
    private var _lemmaIndex = emptyMap<String, Int>()
    private var _ngramIndex = emptyMap<String, Int>()

    // ── loading ──────────────────────────────────────────────────────────────

    /**
     * Synchronous load — call from a background thread or during app start.
     * @param langCode  ISO-639-1 code, e.g. "it", "en", "de"
     */
    fun load(langCode: String) {
        val lang = normaliseLang(langCode)
        Log.i(TAG, "Loading Bliss assets for lang=$lang")

        _names      = loadNames()
        _synsets    = loadSynsets()
        _lexicon    = loadLexicon(lang)
        _lemmaIndex = loadLemmas(lang)
        _ngramIndex = loadNgrams(lang)

        isReady = true
        Log.i(TAG, "Bliss assets loaded: names=${_names.size}, lexicon=${_lexicon.size}, " +
                "lemmas=${_lemmaIndex.size}, ngrams=${_ngramIndex.size}")
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

    fun lookupLemma(lemma: String): Int? = _lemmaIndex[lemma.lowercase(Locale.ROOT)]

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
        val map = HashMap<Int, String>(6000)
        readJsonObject("bliss/bci_names.json").run {
            keys().forEach { key ->
                map[key.toIntOrNull() ?: return@forEach] = getString(key)
            }
        }
        return map
    }

    private fun loadSynsets(): Map<Int, Long> {
        val map = HashMap<Int, Long>(5200)
        readJsonObject("bliss/bci_blissnet.json").run {
            keys().forEach { key ->
                val id = key.toIntOrNull() ?: return@forEach
                map[id] = getLong(key)
            }
        }
        return map
    }

    private fun loadLexicon(lang: String): Map<String, Int> {
        val map = HashMap<String, Int>(12000)
        readJsonObject("bliss/bci_lexicon_$lang.json").run {
            keys().forEach { key ->
                map[key.lowercase(Locale.ROOT)] = getInt(key)
            }
        }
        return map
    }

    private fun loadLemmas(lang: String): Map<String, Int> {
        val map = HashMap<String, Int>(12000)
        readCsv("bliss/lemmas_$lang.csv").forEach { cols ->
            if (cols.size >= 3) {
                val lemma = cols[0].lowercase(Locale.ROOT)
                val id    = cols[2].toIntOrNull() ?: return@forEach
                // lower-POS key gives room for POS-aware lookup later
                map[lemma] = id
            }
        }
        return map
    }

    private fun loadNgrams(lang: String): Map<String, Int> {
        val map = HashMap<String, Int>(500)
        readCsv("bliss/ngrams_multilang.csv").forEach { cols ->
            if (cols.size >= 3 && cols[0] == lang) {
                val phrase = cols[1].lowercase(Locale.ROOT)
                val id     = cols[2].toIntOrNull() ?: return@forEach
                map[phrase] = id
            }
        }
        return map
    }

    // ── asset I/O ─────────────────────────────────────────────────────────────

    private fun readJsonObject(assetPath: String): JSONObject {
        val text = context.assets.open(assetPath).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }
        return JSONObject(text)
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
