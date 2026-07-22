package com.blueapps.egyptianwriter.bliss

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Loads and queries the WordNet-derived assets that power
 * [BlissSemanticComposer]'s Stage A substitution: replacing a word with no
 * direct Bliss symbol by a synonym or nearby hypernym that *does* have one
 * (e.g. Italian "oceano" -> "mare"), instead of falling straight through to
 * UNKNOWN.
 *
 * ## Why this exists (EG audit, 2026-07-22)
 * The original Stage A/B (Patch 5) tried to resolve this by re-querying
 * [BlissLookup.lookupSurface]/[BlissLookup.lookupLemma] on the *same* word
 * that tiers 3a/3c of [BlissTranslator] had already tried and failed on —
 * a precondition that can never hold at the real call site, making both
 * stages permanently unreachable in production. See the full root-cause
 * analysis and empirical coverage measurements in
 * `Report_EG_Tier3g_Opzioni_A_D.md`. This class is the data layer for the
 * redesign: instead of re-querying the Bliss lexicon, it queries a
 * *separate* word -> WordNet-synset -> Bliss-symbol path, built entirely
 * offline by `tools/wordnet_build.py` (see that script's docstring for the
 * full data pipeline and source licensing).
 *
 * ## Assets (per language, plus one shared file)
 * - `wordnet/word2synsets_<lang>.json` — `"word" -> ["13776971-n", ...]`,
 *   in first-sense order (a proxy for "most common meaning first", since
 *   the source data carries no explicit frequency rank).
 * - `wordnet/synset2bliss_<lang>.json` — `"13776971-n" -> [12335, 14990]`,
 *   precomputed at build time; only synsets with >=1 Bliss-lexicon hit are
 *   present (the large majority have none).
 * - `wordnet/hypernyms.json` — **shared across every language** (synset ids
 *   are Princeton WordNet 3.0 offsets, language-independent). Nouns and
 *   verbs only: WordNet adjectives/adverbs use "similar_to", not a clean
 *   is-a hypernym chain, so climbing them would not carry the "X is a kind
 *   of Y" guarantee this substitution relies on.
 *
 * Not every app-supported language has these assets bundled yet — see
 * `OMW_LANG_DIR` in `tools/wordnet_build.py` for which do. [load] simply
 * leaves the maps empty for a language with no bundled assets (logged at
 * INFO, not a hard error), and [findSubstitute] then always returns
 * `null` — Stage A degrades to the same silent no-op the whole tier used
 * to be for every language, just scoped to the languages not yet built.
 *
 * ## Hop cap
 * [findSubstitute] climbs at most [MAX_HYPERNYM_LEVELS] hypernym hops
 * (default 2) before giving up. This is not arbitrary: coverage gains
 * shrink and semantic fidelity degrades monotonically with each hop (see
 * the report's §4.3 quality analysis), and beyond ~2-3 hops most concepts
 * bottom out at near-meaningless generics ("entity", "object").
 *
 * ## Thread-safety
 * Same pattern as [BlissLookup.Tables]: all three maps are consolidated
 * into one immutable [Tables] snapshot published through a single
 * `@Volatile` reference, so [load] can run on a background thread while
 * [findSubstitute] is called concurrently from a translation coroutine
 * without readers ever observing a half-updated state.
 */
class WordNetIndex(private val context: Context) {

    private data class Tables(
        val word2synsets: Map<String, List<String>> = emptyMap(),
        val synset2bliss: Map<String, List<Int>>     = emptyMap(),
        val hypernyms:    Map<String, List<String>>  = emptyMap()
    )

    @Volatile private var _tables = Tables()

    /** ISO-639-1 code of the last successfully loaded language, or `null` before the first [load]. */
    @Volatile var currentLang: String? = null
        private set

    /** `true` once [load] has completed at least once (even if the resulting maps ended up empty). */
    val isLoaded: Boolean get() = currentLang != null

    /**
     * Loads the WordNet assets for [lang]. Safe to call from any thread;
     * cheap enough to call synchronously alongside [BlissLookup.load] since
     * it's the same "read a couple of JSON assets" cost class.
     *
     * Missing per-language assets (a language `tools/wordnet_build.py`
     * hasn't been run for yet) are not an error: the maps are simply left
     * empty and [findSubstitute] always returns `null` for that language.
     */
    fun load(lang: String) {
        val word2synsets = readJsonListOfStringMap("wordnet/word2synsets_$lang.json")
        val synset2bliss = readJsonListOfIntMap("wordnet/synset2bliss_$lang.json")
        val hypernyms     = readJsonListOfStringMap("wordnet/hypernyms.json")
        _tables = Tables(word2synsets, synset2bliss, hypernyms)
        currentLang = lang
        if (word2synsets.isEmpty()) {
            Log.i(TAG, "WordNetIndex: no wordnet assets bundled for lang=$lang — Stage A substitution disabled for this language")
        } else {
            Log.i(TAG, "WordNetIndex loaded for lang=$lang: ${word2synsets.size} words, " +
                    "${synset2bliss.size} bliss-linked synsets, ${hypernyms.size} hypernym entries")
        }
    }

    fun reset() {
        _tables = Tables()
        currentLang = null
    }

    /** Result of a successful [findSubstitute] call. */
    data class Substitute(
        /** BCI-AV id of the substitute symbol. */
        val bciAvId: Int,
        /** The WordNet synset that produced the match (for logging/debugging). */
        val synset: String,
        /** 0 = direct synonym (same synset as the original word); 1, 2, ... = hypernym hops. */
        val level: Int
    )

    /**
     * Finds a Bliss-symbol substitute for [word] via direct synonyms first,
     * then progressively broader hypernyms, up to [MAX_HYPERNYM_LEVELS]
     * hops. Returns the *first* hit found at the lowest (most specific)
     * level available — i.e. a direct synonym always wins over any
     * hypernym, and a closer hypernym always wins over a more distant one.
     *
     * @param word  Lower-cased surface token (same contract as
     *              [BlissSemanticComposer.composeStructured]'s `word` param
     *              — already failed [BlissLookup.lookupSurface] /
     *              [BlissLookup.lookupLemma] in earlier tiers).
     * @return      A [Substitute], or `null` if [word] is absent from this
     *              language's WordNet data, or no synonym/hypernym within
     *              the hop cap has a Bliss symbol.
     */
    fun findSubstitute(word: String): Substitute? {
        val snapshot = _tables
        val ownSynsets = snapshot.word2synsets[word.lowercase(Locale.ROOT)] ?: return null

        // Level 0 — direct synonym: does any of word's own synsets already
        // have a Bliss-lexicon hit?
        for (synset in ownSynsets) {
            snapshot.synset2bliss[synset]?.firstOrNull()?.let { id ->
                return Substitute(bciAvId = id, synset = synset, level = 0)
            }
        }

        // Levels 1..MAX — broaden one hypernym hop at a time (BFS), first
        // hit at the lowest level wins.
        var frontier: Set<String> = ownSynsets.toSet()
        val seen = frontier.toMutableSet()
        for (level in 1..MAX_HYPERNYM_LEVELS) {
            val next = LinkedHashSet<String>()
            for (synset in frontier) {
                snapshot.hypernyms[synset]?.forEach { hyper -> if (seen.add(hyper)) next += hyper }
            }
            if (next.isEmpty()) break
            for (synset in next) {
                snapshot.synset2bliss[synset]?.firstOrNull()?.let { id ->
                    return Substitute(bciAvId = id, synset = synset, level = level)
                }
            }
            frontier = next
        }
        return null
    }

    // ── asset loading ─────────────────────────────────────────────────────────

    private fun readJsonListOfStringMap(assetPath: String): Map<String, List<String>> {
        val json = readJsonObjectOrNull(assetPath) ?: return emptyMap()
        val map = HashMap<String, List<String>>(json.length())
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = json.optJSONArray(key) ?: continue
            map[key] = arr.toStringList()
        }
        return map
    }

    private fun readJsonListOfIntMap(assetPath: String): Map<String, List<Int>> {
        val json = readJsonObjectOrNull(assetPath) ?: return emptyMap()
        val map = HashMap<String, List<Int>>(json.length())
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = json.optJSONArray(key) ?: continue
            map[key] = arr.toIntList()
        }
        return map
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optString(i, null) }

    private fun JSONArray.toIntList(): List<Int> =
        (0 until length()).map { i -> optInt(i) }

    /**
     * Missing assets are expected (see [load]'s KDoc — not every language
     * has been built yet) so this returns `null` silently on
     * [IOException]; malformed JSON is logged as an error since that
     * indicates a real build-pipeline bug, not an absent-by-design asset.
     */
    private fun readJsonObjectOrNull(assetPath: String): JSONObject? =
        try {
            context.assets.open(assetPath).use { stream ->
                JSONObject(stream.bufferedReader(Charsets.UTF_8).readText())
            }
        } catch (_: IOException) { null }
          catch (e: org.json.JSONException) {
              Log.e(TAG, "Malformed JSON in $assetPath", e); null
          }

    companion object {
        private const val TAG = "WordNetIndex"
        const val MAX_HYPERNYM_LEVELS = 2
    }
}
