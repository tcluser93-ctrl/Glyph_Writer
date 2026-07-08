package com.blueapps.egyptianwriter.bliss

import android.util.Log
import java.util.Locale

/**
 * Tier 3g — Semantic composition helper.
 *
 * Replaces the legacy orthographic pivot-split with a three-stage strategy
 * that respects the BCI/Bliss compositional principles:
 *
 * ## Stage A — Direct synset match (WordNet/BlissNet)
 * If the input token can be resolved to a BCI-AV ID (surface or lemma lookup)
 * and that ID has a known WordNet synset offset in `bci_blissnet.json`, the
 * inverted synset index is consulted to return a symbol whose synset directly
 * matches.  This is a pure semantic hit — no new composition, just a reliable
 * cross-lingual link via BlissNet.
 *
 * ## Stage B — Hypernym classifier (semantic bucket)
 * When Stage A misses, the synset offset of the resolved token is mapped to a
 * WordNet semantic bucket (noun coarse-range / verb coarse-range) and compared
 * against the synsets of all BCI IDs in that bucket.  The closest BCI symbol
 * (lowest |synset delta| within the bucket) is used as a **classifier** symbol
 * to build a two-element SEMANTIC composition: `[classifier, specifier]`, where
 * the specifier is the directly-resolved BCI ID if available, or null.
 *
 * ## Stage C — Orthographic pivot-split (legacy fallback, off by default)
 * The original exhaustive pivot-split over grapheme substrings.  Not BCI-
 * conformant but useful for maximising coverage in practice.  Disabled by
 * default via [enableOrthographicFallback].  When enabled, results carry
 * [BlissSymbol.MatchType.COMPOUND] to distinguish them from Stage A/B results
 * that carry [BlissSymbol.MatchType.SEMANTIC].
 *
 * ## Thread-safety
 * This class is stateless after construction (indices are lazy `val`).
 * Safe to share across coroutines.
 *
 * @param lookup                    A ready [BlissLookup] instance.
 * @param enableOrthographicFallback  When `true`, Stage C runs after A+B fail.
 *                                    Defaults to `false` (BCI-clean mode).
 */
class BlissSemanticComposer(
    private val lookup: BlissLookup,
    val enableOrthographicFallback: Boolean = false
) {

    // ── inverted synset index: synsetOffset -> list of BCI-AV IDs ────────────

    /**
     * Built lazily from [BlissLookup.synsets].
     * Maps WordNet synset offset → list of BCI-AV IDs that share it.
     */
    private val synsetToBciIds: Map<Long, List<Int>> by lazy {
        lookup.synsets
            .entries
            .filter { it.value >= 0L }
            .groupBy(keySelector = { it.value }, valueTransform = { it.key })
    }

    // ── public entry point ────────────────────────────────────────────────────

    /**
     * Attempts to compose [word] into a [BlissSymbol] using semantic strategies.
     *
     * @param word  Lower-cased surface token that has already failed tiers 3a–3f.
     * @param lang  ISO-639-1 language code (informational; lookup is lang-independent
     *              because [BlissLookup] is already initialised for the active language).
     * @return      A [BlissSymbol] (SEMANTIC or COMPOUND match type), or `null` if
     *              all stages fail.
     */
    fun compose(word: String, @Suppress("UNUSED_PARAMETER") lang: String): BlissSymbol? {
        val w = word.lowercase(Locale.ROOT)
        if (w.isBlank()) return null

        // Stage A — direct synset match
        stageA(w)?.let { return it }

        // Stage B — hypernym classifier via synset bucket
        stageB(w)?.let { return it }

        // Stage C — orthographic pivot-split (optional legacy fallback)
        if (enableOrthographicFallback && w.length >= MIN_WORD_LEN) {
            stageC(w, word)?.let { return it }
        }

        Log.v(TAG, "compose: all stages failed for '$w'")
        return null
    }

    // ── Stage A ───────────────────────────────────────────────────────────────

    /**
     * Resolves [word] to a BCI-AV ID and then checks whether the inverted synset
     * index contains that same synset offset.  On a hit, returns a [BlissSymbol]
     * whose [BlissSymbol.matchType] is [BlissSymbol.MatchType.SEMANTIC].
     *
     * The logic:
     * 1. Try surface lookup then lemma lookup → candidate BCI ID
     * 2. Get its synset offset via [BlissLookup.synsetOf]
     * 3. Find all BCI IDs sharing that synset; prefer the candidate itself,
     *    otherwise pick the first alternative.
     */
    private fun stageA(word: String): BlissSymbol? {
        val candidateId = lookup.lookupSurface(word) ?: lookup.lookupLemma(word) ?: return null
        val synset = lookup.synsetOf(candidateId)
        if (synset < 0L) return null

        val matchId = synsetToBciIds[synset]
            ?.let { ids -> ids.firstOrNull { it == candidateId } ?: ids.firstOrNull() }
            ?: return null

        Log.d(TAG, "stageA: '$word' → BCI $matchId (synset=$synset)")
        return lookup.toSymbol(
            id     = matchId,
            source = word,
            lemma  = word,
            mt     = BlissSymbol.MatchType.SEMANTIC
        )
    }

    // ── Stage B ───────────────────────────────────────────────────────────────

    /**
     * Attempts a hypernym-level semantic match.
     *
     * When the token resolves to a BCI ID with a synset, we search the inverted
     * index for the BCI symbol whose synset offset is closest within the same
     * WordNet semantic bucket (coarse noun/verb/adj offset ranges).  That symbol
     * acts as the BCI **classifier**.  If a direct specifier BCI ID is also
     * available, a two-element SEMANTIC composition is returned; otherwise the
     * classifier alone is returned as a best-effort semantic approximation.
     *
     * WordNet offset buckets (approximate, Princeton WN 3.1):
     * - Nouns:  100 000 000 – 113 999 999
     * - Verbs:  200 000 000 – 202 999 999
     * - Adj:    300 000 000 – 302 999 999
     * - Adv:    400 000 000 – 402 999 999
     */
    private fun stageB(word: String): BlissSymbol? {
        val specifierId = lookup.lookupSurface(word) ?: lookup.lookupLemma(word) ?: return null
        val specSynset  = lookup.synsetOf(specifierId)
        if (specSynset < 0L) return null

        val bucket = wordnetBucket(specSynset)

        // Find BCI ID whose synset is closest within the same bucket
        var bestId    = -1
        var bestDelta = Long.MAX_VALUE
        for ((bciId, bciSynset) in lookup.synsets) {
            if (wordnetBucket(bciSynset) != bucket) continue
            val delta = kotlin.math.abs(bciSynset - specSynset)
            if (delta < bestDelta) {
                bestDelta = delta
                bestId    = bciId
            }
        }
        if (bestId < 0) return null

        val classifierSynset = lookup.synsetOf(bestId)
        Log.d(TAG, "stageB: '$word' → classifier BCI $bestId (synset=$classifierSynset, " +
                "specifier BCI $specifierId, delta=$bestDelta)")

        // Build a SEMANTIC composition [classifier, specifier] if they differ
        return if (bestId != specifierId) {
            val classifierName = lookup.nameOf(bestId)
            val specifierName  = lookup.nameOf(specifierId)
            BlissSymbol(
                bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
                name         = "$classifierName+$specifierName",
                synsetId     = specSynset,
                sourceWord   = word,
                lemma        = word,
                matchType    = BlissSymbol.MatchType.SEMANTIC,
                componentIds = listOf(bestId, specifierId)
            )
        } else {
            // Classifier == specifier: single-symbol approximation
            lookup.toSymbol(
                id     = bestId,
                source = word,
                lemma  = word,
                mt     = BlissSymbol.MatchType.SEMANTIC
            )
        }
    }

    // ── Stage C (legacy orthographic pivot-split) ─────────────────────────────

    /**
     * Exhaustive pivot-split over grapheme substrings.
     * Retained as an opt-in fallback ([enableOrthographicFallback] == `true`).
     * Results carry [BlissSymbol.MatchType.COMPOUND] to distinguish them from
     * semantically grounded Stage A/B results.
     */
    private fun stageC(w: String, originalWord: String): BlissSymbol? {
        for (pivot in MIN_FRAGMENT_LEN..(w.length - MIN_FRAGMENT_LEN)) {
            val left  = w.substring(0, pivot)
            val right = w.substring(pivot)

            val leftId  = resolveFragment(left)  ?: continue
            val rightId = resolveFragment(right) ?: continue

            val composedName = "${lookup.nameOf(leftId)}+${lookup.nameOf(rightId)}"
            Log.d(TAG, "stageC: '$w' → '$left'($leftId) + '$right'($rightId)")
            return BlissSymbol(
                bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
                name         = composedName,
                sourceWord   = originalWord,
                lemma        = originalWord,
                matchType    = BlissSymbol.MatchType.COMPOUND,
                componentIds = listOf(leftId, rightId)
            )
        }
        Log.v(TAG, "stageC: no valid split for '$w'")
        return null
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun resolveFragment(fragment: String): Int? {
        if (fragment.length < MIN_FRAGMENT_LEN) return null
        return lookup.lookupSurface(fragment) ?: lookup.lookupLemma(fragment)
    }

    /**
     * Returns a coarse bucket identifier for a WordNet synset offset.
     * Used by Stage B to restrict the hypernym search to the same POS class.
     */
    private fun wordnetBucket(synset: Long): Int = when {
        synset in 100_000_000L..113_999_999L -> BUCKET_NOUN
        synset in 200_000_000L..202_999_999L -> BUCKET_VERB
        synset in 300_000_000L..302_999_999L -> BUCKET_ADJ
        synset in 400_000_000L..402_999_999L -> BUCKET_ADV
        else                                 -> BUCKET_OTHER
    }

    companion object {
        private const val TAG              = "BlissSemanticComposer"
        private const val MIN_WORD_LEN     = 6
        private const val MIN_FRAGMENT_LEN = 3

        // WordNet semantic bucket constants
        private const val BUCKET_NOUN  = 1
        private const val BUCKET_VERB  = 2
        private const val BUCKET_ADJ   = 3
        private const val BUCKET_ADV   = 4
        private const val BUCKET_OTHER = 0
    }
}
