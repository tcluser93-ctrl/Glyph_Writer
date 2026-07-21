package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * All six lookup maps are consolidated into one immutable [Tables] snapshot,
 * published via a single `@Volatile` reference ([_tables]). [load] builds a
 * complete new [Tables] instance from local variables and publishes it with
 * one assignment, so any reader on any thread — with no locking required on
 * the read side — always sees either the previous or the new snapshot in
 * full, never a partially-updated mix of the two. Writers (i.e. concurrent
 * [load] calls) are additionally serialised by [loadMutex].
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

    /**
     * Immutable snapshot of all six lookup maps, published as a single unit.
     *
     * ## Fix (audit EG, 2026-07-21)
     * Prior to this, each map lived in its own `@Volatile` field. `@Volatile`
     * only guarantees that *a single field's* read/write is visible across
     * threads — it does **not** make a group of field writes atomic as a
     * whole. [load] wrote all six fields one after another; a reader on
     * another thread (any [lookupSurface] / [lookupLemma] / [lookupNgram] /
     * … call made from the UI or a translation coroutine while [load] for a
     * *new* language is concurrently in flight) could observe a torn,
     * half-updated state — e.g. `lexicon` already replaced for the new
     * language while `ngramIndex` still holds the old one — even though
     * [loadMutex] already serialises concurrent *writers* against each
     * other, because it does nothing to protect readers that never take the
     * lock (and correctly shouldn't have to, for a hot lookup path).
     *
     * Consolidating all six maps into one [Tables] value class and
     * publishing it via a single `@Volatile` reference restores per-call
     * atomicity for readers: any single read of [_tables] always yields a
     * fully self-consistent set of maps from exactly one [load] invocation,
     * never a mix of two.
     */
    private data class Tables(
        val names:         Map<Int, String>  = emptyMap(),
        val synsets:       Map<Int, Long>    = emptyMap(),
        val lexicon:       Map<String, Int>  = emptyMap(),
        val lemmaIndex:    Map<String, Int>  = emptyMap(),
        val lemmaPoSIndex: Map<String, Int>  = emptyMap(),
        val ngramIndex:    Map<String, Int>  = emptyMap()
    )

    // ── public read-only views ───────────────────────────────────────────────

    /** BCI-AV ID → canonical English name. */
    val names:        Map<Int, String>  get() = _tables.names
    /** BCI-AV ID → WordNet synset offset (-1 if absent). */
    val synsets:      Map<Int, Long>    get() = _tables.synsets
    /** Surface word → BCI-AV ID (language-specific, lower-cased). */
    val lexicon:      Map<String, Int>  get() = _tables.lexicon
    /** Lemma → BCI-AV ID (language-specific, lower-cased). */
    val lemmaIndex:   Map<String, Int>  get() = _tables.lemmaIndex
    /**
     * POS-aware lemma index.  Key = `"lemma|POS"` (e.g. `"camminare|V"`).
     * POS codes: `N V A R P D C I X`.
     */
    val lemmaPoSIndex: Map<String, Int> get() = _tables.lemmaPoSIndex
    /** N-gram phrase → BCI-AV ID (language-specific, lower-cased). */
    val ngramIndex:    Map<String, Int> get() = _tables.ngramIndex

    /** ISO-639-1 code of the last successfully loaded language. */
    @Volatile var currentLang: String? = null
        private set

    /** `true` once [load] completes without error. */
    @Volatile var isReady: Boolean = false
        private set

    // ── private backing fields ───────────────────────────────────────────────

    /** Single atomically-published snapshot of all six lookup maps. See [Tables]. */
    @Volatile private var _tables = Tables()

    /** Room FTS4 database instance (lazy-initialised after first load). */
    @Volatile private var _db: BlissDatabase? = null

    /**
     * Serialises the whole "check current state → load assets → publish"
     * transaction in [loadAsync] / [loadIfNeeded].
     *
     * ## Fix (enterprise-grade audit, 2026-07-20)
     * Each backing field above is individually `@Volatile`, which only
     * guarantees that a single field read/write is visible across threads —
     * it does **not** make the group of six field writes performed by [load]
     * atomic as a whole, and it does **not** make the
     * `isReady && currentLang == lang` check in [loadIfNeeded] atomic with
     * respect to a concurrently-running [load].
     *
     * `BlissLookup` is a process-wide singleton ([getInstance]) and
     * [loadIfNeeded] is re-invoked every time a new `BlissTranslateFragment`
     * is created (e.g. `MainActivity.navigateTo()` always builds a fresh
     * instance for `nav_translate`). Navigating away from and back to the
     * translator screen while the very first cold-start asset load is still
     * in flight — a normal, reachable user action, not a contrived edge
     * case — used to launch a **second**, fully concurrent [load] on this
     * singleton. For the same language that was merely wasted CPU/IO; the
     * moment two *different* languages race here (e.g. once a language
     * switcher is wired up, or the system locale changes mid-session), the
     * six field writes from both calls can interleave field-by-field,
     * leaving `_lexicon` from one language paired with `_ngramIndex` from
     * another under a single `currentLang` — a silently inconsistent lookup
     * table that produces wrong translations or downstream crashes, and is
     * effectively impossible to reproduce on demand.
     *
     * Guarding the full transaction with this [Mutex] makes concurrent
     * callers either (a) become genuinely sequential — the second caller's
     * re-check inside the lock sees the first caller's now-published,
     * fully-consistent state and skips redundant work — or (b) fully
     * serialised loads when the language actually differs, so no partial/
     * mixed state is ever observable.
     */
    private val loadMutex = Mutex()

    // ── custom exception ─────────────────────────────────────────────────────

    class LoadException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Idempotent load.  No-op when [lang] equals [currentLang] **and** [isReady].
     *
     * This unlocked pre-check is a pure optimisation: it lets the extremely
     * common case (language already loaded — true on essentially every
     * Fragment recreation after the first) return immediately without
     * launching a coroutine or touching [loadMutex] at all. Its outcome does
     * not need to be perfectly up to date, because every path — whether this
     * check hits or misses — funnels through [loadAsync], which re-validates
     * the very same condition *inside* [loadMutex] before doing any real
     * work. A stale "not ready" here just costs one redundant (harmless)
     * mutex acquisition; it can never cause a redundant *load*, let alone a
     * corrupted one.
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
     * Loads [lang] on [scope] (`Dispatchers.IO`), then dispatches [onReady] /
     * [onError] on `Dispatchers.Main`.
     *
     * The actual [load] call happens inside [loadMutex], with the
     * ready/language check repeated *inside* the lock. This guarantees that
     * concurrent invocations — from [loadIfNeeded] racing on Fragment
     * recreation, from a direct [loadAsync] call, or both — can never run
     * [load] in parallel with each other: they either serialise onto
     * genuinely different loads, or the loser of the race simply observes
     * the winner's already-published, fully-consistent state and skips its
     * own [load] call entirely.
     */
    fun loadAsync(
        lang:    String,
        scope:   CoroutineScope,
        onReady: () -> Unit = {},
        onError: (Throwable) -> Unit = { Log.e(TAG, "Load error", it) }
    ) {
        val normalised = normaliseLang(lang)
        scope.launch(Dispatchers.IO) {
            try {
                loadMutex.withLock {
                    // Re-check inside the lock: another coroutine may have
                    // already loaded this exact language while we were
                    // waiting to acquire the mutex — in that case skip the
                    // redundant (and potentially racy) reload entirely.
                    if (!(isReady && currentLang == normalised)) {
                        load(normalised)
                    }
                }
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
        val newTables: Tables
        try {
            val names   = loadNames()
            val synsets = loadSynsets()
            val lexicon = loadLexicon(lang)
            val (plain, pos) = loadLemmas(lang)
            val ngrams  = loadNgrams(lang)
            // Built entirely from local vals — nothing is published to
            // readers until the single assignment below.
            newTables = Tables(
                names         = names,
                synsets       = synsets,
                lexicon       = lexicon,
                lemmaIndex    = plain,
                lemmaPoSIndex = pos,
                ngramIndex    = ngrams
            )
        } catch (io: IOException) {
            isReady     = false
            currentLang = null
            throw LoadException("Failed to load Bliss assets for lang=$lang", io)
        }
        // Single @Volatile write: any concurrent reader sees either the
        // previous, fully-consistent Tables or this new one — never a mix.
        _tables     = newTables
        currentLang = lang
        isReady     = true
        Log.i(TAG, "Bliss assets loaded: names=${newTables.names.size}, " +
                "lexicon=${newTables.lexicon.size}, lemmas=${newTables.lemmaIndex.size}, " +
                "ngrams=${newTables.ngramIndex.size}")
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
        val snapshot = _tables
        BlissDatabase.populateIfEmpty(db, snapshot.lexicon, snapshot.lemmaIndex, lang)
    }

    fun reset() {
        _tables     = Tables()
        currentLang = null
        isReady     = false
        Log.d(TAG, "BlissLookup reset")
    }

    // ── HashMap lookup helpers (sync, tiers 1-3) ─────────────────────────────

    fun nameOf(id: Int): String = _tables.names[id] ?: id.toString()
    fun synsetOf(id: Int): Long = _tables.synsets[id] ?: -1L

    fun lookupSurface(word: String): Int? = _tables.lexicon[word.lowercase(Locale.ROOT)]
    fun lookupLemma(lemma: String): Int?  = _tables.lemmaIndex[lemma.lowercase(Locale.ROOT)]

    fun lookupLemmaPos(lemma: String, pos: String): Int? {
        // Single snapshot read so both lookups below are guaranteed to come
        // from the same Tables instance (see [Tables] KDoc).
        val snapshot = _tables
        val key = "${lemma.lowercase(Locale.ROOT)}|${pos.uppercase(Locale.ROOT)}"
        return snapshot.lemmaPoSIndex[key] ?: snapshot.lemmaIndex[lemma.lowercase(Locale.ROOT)]
    }

    fun lookupNgram(phrase: String): Int? = _tables.ngramIndex[phrase.lowercase(Locale.ROOT)]

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
