package com.blueapps.egyptianwriter.bliss

import android.util.Log
import java.util.Locale

/**
 * Tier 4 — Semantic composition helper.
 *
 * Attempts to decompose an unknown surface word into two or more lexically
 * meaningful fragments that each resolve to a known Bliss symbol.  When at
 * least two valid fragments are found, a synthetic [BlissSymbol] of type
 * [BlissSymbol.MatchType.COMPOUND] is returned with its [BlissSymbol.componentIds]
 * list populated in the order the fragments appear in the original word.
 *
 * Returns `null` when:
 * - fewer than two fragments resolve to a known BCI-AV ID, or
 * - the word is too short to split meaningfully (< [MIN_WORD_LEN] chars).
 *
 * ## Strategy
 *
 * The current implementation uses an **exhaustive pivot-split** approach:
 * for each possible split point from [MIN_FRAGMENT_LEN] to `word.length -
 * [MIN_FRAGMENT_LEN]`, the left and right substrings are each looked up in
 * the lexicon (surface → BCI-AV) and lemma index.  The first split that
 * yields two valid IDs is used.
 *
 * This is deliberately conservative and language-agnostic.  It intentionally
 * avoids any morphological assumptions so that it works across all 8 supported
 * languages without requiring language-specific rules.
 *
 * The strategy can be extended in future patches (e.g. Patch 4) to support
 * three-way splits, prefix/suffix stripping, or morpheme-aware decomposition
 * while keeping this class as the single extension point for tier 4.
 *
 * ## Thread-safety
 * This class is stateless after construction and can be shared across coroutines.
 *
 * @param lookup  A [BlissLookup] instance that is already in the [BlissLookup.isReady]
 *                state.  The composer reads from the in-memory maps only (sync path),
 *                so it is safe to call from any thread or coroutine context.
 */
class BlissSemanticComposer(private val lookup: BlissLookup) {

    /**
     * Attempts to compose [word] into a [BlissSymbol] of type
     * [BlissSymbol.MatchType.COMPOUND].
     *
     * @param word  The lower-cased surface token that failed all upstream tiers.
     * @param lang  ISO-639-1 language code (informational only in this tier;
     *              lookup is language-independent because [BlissLookup] is already
     *              initialised for the correct language).
     * @return      A [BlissSymbol] with [BlissSymbol.matchType] ==
     *              [BlissSymbol.MatchType.COMPOUND] and at least two entries in
     *              [BlissSymbol.componentIds], or `null` if no valid split was found.
     */
    fun compose(word: String, @Suppress("UNUSED_PARAMETER") lang: String): BlissSymbol? {
        val w = word.lowercase(Locale.ROOT)
        if (w.length < MIN_WORD_LEN) return null

        for (pivot in MIN_FRAGMENT_LEN..(w.length - MIN_FRAGMENT_LEN)) {
            val left  = w.substring(0, pivot)
            val right = w.substring(pivot)

            val leftId  = resolveFragment(left)  ?: continue
            val rightId = resolveFragment(right) ?: continue

            // Both fragments resolved — build the compound symbol.
            val componentIds = listOf(leftId, rightId)
            val composedName = buildName(leftId, rightId)
            Log.d(TAG, "compose: '$w' → '$left'($leftId) + '$right'($rightId)")
            return BlissSymbol(
                bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
                name         = composedName,
                sourceWord   = word,
                lemma        = word,
                matchType    = BlissSymbol.MatchType.COMPOUND,
                componentIds = componentIds
            )
        }

        Log.v(TAG, "compose: no valid split found for '$w'")
        return null
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Tries to resolve [fragment] via surface lookup first, then lemma lookup.
     * Returns the BCI-AV ID on the first hit, or `null` if neither lookup succeeds.
     */
    private fun resolveFragment(fragment: String): Int? {
        if (fragment.length < MIN_FRAGMENT_LEN) return null
        return lookup.lookupSurface(fragment)
            ?: lookup.lookupLemma(fragment)
    }

    /** Builds a human-readable composite name from the two component IDs. */
    private fun buildName(leftId: Int, rightId: Int): String {
        val leftName  = lookup.nameOf(leftId)
        val rightName = lookup.nameOf(rightId)
        return "$leftName+$rightName"
    }

    companion object {
        private const val TAG              = "BlissSemanticComposer"
        /** Minimum total word length before composition is attempted. */
        private const val MIN_WORD_LEN     = 6
        /** Minimum length of each individual fragment. */
        private const val MIN_FRAGMENT_LEN = 3
    }
}
