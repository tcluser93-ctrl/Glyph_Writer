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
 * | `bci_names_{lang}.json` | `{"12335":"camminare", …}` | BCI-AV ID → display name in `lang` (audit EG, 2026-07-22 — see [loadNames]'s KDoc; falls back to legacy English-only `bci_names.json` if missing) |
 * | `bci_blissnet.json` | `{"12335": ["06856067", …], …}` | BCI-AV ID → WordNet synset offset(s) |
 * | `bci_lexicon_{lang}.json` | `{"walk": [12335], …}` | surface word → BCI-AV ID |
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
            val names   = loadNames(lang)
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

    /**
     * Loads the BCI-AV id → display name map for [lang].
     *
     * ## Fix (audit EG, 2026-07-22)
     * Previously always read `bci_names.json` — a single English-only
     * id→name map — regardless of [lang], so every symbol's gloss (card
     * text, chip text, TTS) was always English even when translating into
     * Italian, German, etc. `bci_full.json` (3.75 MB, all 17 upstream
     * languages) already carried the data needed to fix this, but was never
     * wired up. Rather than parsing the full multi-language blob on every
     * language switch (wasteful: 1/17th of it is used, every time),
     * `tools/bci_names_split.py` pre-splits it at build time into compact
     * `bci_names_<lang>.json` files — same pattern as the WordNet Stage A
     * assets (`tools/wordnet_build.py`). Every id is guaranteed a non-blank
     * name in every generated file (falls back to English at build time for
     * ids missing a translation — see that script's docstring for exact
     * coverage numbers per language and the Polish "po"-vs-"pl" data-quality
     * note), so no per-lookup fallback is needed here.
     *
     * Defensively falls back to the legacy English-only `bci_names.json` if
     * `bci_names_<lang>.json` isn't bundled for some reason (e.g. a future
     * supported language added to [SUPPORTED_LANGS] before its names file is
     * generated) — better to show English glosses than none at all.
     */
    private fun loadNames(lang: String): Map<Int, String> {
        val map = HashMap<Int, String>(7000)
        val primaryAsset = "bliss/bci_names_$lang.json"
        val json = readJsonObjectOrNull(primaryAsset)
            ?: readJsonObjectOrNull("bliss/bci_names.json").also {
                if (it != null) {
                    Log.w(TAG, "$primaryAsset not found — falling back to legacy English-only bci_names.json")
                } else {
                    Log.w(TAG, "$primaryAsset not found and no bci_names.json fallback either")
                }
            }
        json?.let {
            val keys = it.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id  = key.toIntOrNull() ?: continue
                map[id] = it.optString(key, "").takeIf { name -> name.isNotEmpty() } ?: continue
            }
        }
        return map
    }

    private fun loadSynsets(): Map<Int, Long> {
        val map = HashMap<Int, Long>(6000)
        readJsonObjectOrNull("bliss/bci_blissnet.json")?.let { json ->
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id  = key.toIntOrNull() ?: continue
                val v   = firstSynsetOffset(json.opt(key)) ?: continue
                if (v >= 0L) map[id] = v
            }
        } ?: Log.w(TAG, "bci_blissnet.json not found")
        return map
    }

    /**
     * Extracts the primary WordNet synset offset from a `bci_blissnet.json`
     * entry value.
     *
     * ## Fix (audit EG, 2026-07-22)
     * `bci_blissnet.json` was reduced to an empty stub (`{}`) by a later
     * asset refresh (commit `914e0e3`, 2026-06-30) that overwrote a
     * previously-populated file (5,091 real BCI-AV → WordNet 3.1 synset
     * mappings, added 2026-06-29) because the refresh's xlsx source didn't
     * carry synset data — see `Report_EG_Tier3g_Opzioni_A_D.md`, §2, for the
     * full git-archaeology trail. Restoring that historical data (filtered
     * to the 5,089 of 5,091 ids still present in the current BCI-AV id
     * scheme) also surfaced a *second*, independent problem: each entry's
     * value is a JSON array of zero-padded synset-offset **strings** (e.g.
     * `"8485": ["06857090", "06856067", "07140666"]` — one BCI symbol can
     * correspond to multiple related WordNet senses), never the single
     * scalar number [loadSynsets] used to parse via `JSONObject.optLong`.
     * Same failure mode as the `bci_lexicon_<lang>.json` bug fixed
     * separately (see [firstBciId]): `optLong` silently falls back to `-1`
     * for a non-numeric value including a `JSONArray`, so even a correctly
     * restored file would have parsed to nothing.
     *
     * When multiple synset offsets are listed, the first is used as the
     * primary one, consistent with [firstBciId]'s handling of multi-
     * candidate lexicon entries — [BlissLookup.synsets] and [synsetOf] keep
     * their existing single-`Long`-per-id contract rather than widening to
     * a list, since nothing in the codebase currently needs more than one
     * synset per symbol.
     */
    private fun firstSynsetOffset(value: Any?): Long? = when (value) {
        is Int  -> value.toLong()
        is Long -> value
        is org.json.JSONArray ->
            if (value.length() > 0) value.optString(0, null)?.toLongOrNull() else null
        else -> null
    }

    private fun loadLexicon(lang: String): Map<String, Int> {
        val map = HashMap<String, Int>(15000)
        readJsonObjectOrNull("bliss/bci_lexicon_$lang.json")?.let { json ->
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id  = firstBciId(json.opt(key)) ?: continue
                if (id > 0) map[key.lowercase(Locale.ROOT)] = id
            }
        } ?: Log.w(TAG, "bci_lexicon_$lang.json not found")
        return map
    }

    /**
     * Extracts the primary BCI-AV id from a `bci_lexicon_<lang>.json` entry
     * value.
     *
     * ## Fix (audit EG, 2026-07-22)
     * Every entry in `bci_lexicon_<lang>.json` is, and has always been (going
     * back to the commit that first populated these shards), a JSON array of
     * candidate ids — e.g. `"punto": [8486, 13867]` for an ambiguous word
     * with two possible symbols, `"virgola": [8487]` for an unambiguous one.
     * [loadLexicon] previously called `JSONObject.optInt(key, -1)`, which
     * org.json silently returns as the `-1` fallback for *any* non-numeric
     * value — including a `JSONArray` (verified against the reference
     * org.json implementation, which Android's runtime `org.json` package
     * is sourced from and behaviourally matches for this method). This means
     * `id > 0` was never true, `_lexicon`/[lexicon] was always empty
     * regardless of language, and tier 3a (exact surface match, the most
     * direct lookup in [BlissTranslator]'s pipeline) never matched anything.
     * The bug was easy to miss end-to-end because tier 3c (plain lemma
     * lookup, backed by `lemmas_<lang>.csv` — a different, correctly
     * scalar-parsed asset) still resolves many words that happen to already
     * be in base/lemma form, just with `MatchType.LEMMA` instead of
     * `MatchType.EXACT`. What silently fell through were entries that exist
     * *only* in the JSON lexicon: proper nouns, multi-word idioms (e.g.
     * "punto esclamativo"), and inflected surface forms absent from the
     * lemma CSV.
     *
     * When a lexicon entry lists multiple candidate ids, the first one is
     * used as the primary surface match — consistent with how first-listed
     * order is treated as a "most common sense" proxy for the WordNet-
     * derived assets elsewhere in this codebase. Disambiguating by POS
     * instead is a possible future improvement; not attempted here to keep
     * this fix scoped to restoring the pre-existing single-id contract of
     * [lexicon] (`Map<String, Int>`).
     *
     * Defensively also accepts a bare scalar number, in case a future data
     * refresh reverts to that shape.
     */
    private fun firstBciId(value: Any?): Int? = when (value) {
        is Int  -> value
        is Long -> value.toInt()
        is org.json.JSONArray ->
            if (value.length() > 0) value.optInt(0, -1).takeIf { it > 0 } else null
        else -> null
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
