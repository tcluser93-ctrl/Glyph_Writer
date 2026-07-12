package com.blueapps.egyptianwriter.bliss

/**
 * Immutable value-object representing a single BCI-AV Bliss symbol.
 *
 * ## Enterprise changes (F1-01 / F1-02 / F1-11 / F1-12)
 *
 * - [category] added: semantic category string from bci_names.json
 *   (e.g. "action", "thing", "evaluation"). Required for FALLBACK_CATEGORY lookup.
 * - [synsetId] changed to `Long?` (was `Long = -1L`): idiomatic Kotlin nullable.
 * - [init] validates invariants: bciAvId must be positive; name must be non-blank.
 * - [displayLabel] moved to [BlissSymbolDisplayExt.kt] (Android-only extension)
 *   so this file remains a pure Kotlin class, compilable by JVM unit tests without
 *   an Android classpath.
 *
 * ## Patch 3 additions
 *
 * - [indicators]: per-token morphological indicators (PLURAL / PAST / FUTURE)
 *   derived from the Morfologik FSA tag via [MorfologikTagMapper]. Empty by default.
 *   Set by [withIndicators] in tier 3b of the async pipeline.
 * - [componentIds]: ordered list of BCI-AV IDs that make up this compound symbol
 *   (only populated when [matchType] == [MatchType.COMPOUND] or [MatchType.SEMANTIC]).
 * - [withIndicators]: copy-helper that returns a new instance with [indicators] set.
 * - [isCompound]: convenience property — true when [matchType] == [MatchType.COMPOUND].
 * - [COMPOUND_SYMBOL_ID]: sentinel constant (-2) to identify synthetic compound nodes,
 *   distinct from [UNKNOWN_SYMBOL_ID] (-1-mapped to 17729).
 * - init block relaxed: bciAvId validation skipped for UNKNOWN and COMPOUND symbols
 *   to allow synthetic sentinel IDs.
 *
 * ## Patch 5 additions
 *
 * - [MatchType.SEMANTIC]: new match type produced by [BlissSemanticComposer] Stage A
 *   and Stage B.  Marks compositions grounded in WordNet/BlissNet synset relations
 *   (BCI-conformant) as opposed to [MatchType.COMPOUND] which is used for the
 *   legacy orthographic pivot-split (Stage C, opt-in fallback).
 * - [isSemanticComposition]: convenience property — true when
 *   [matchType] == [MatchType.SEMANTIC].
 * - init block updated: SEMANTIC symbols also skip the positive-bciAvId assertion
 *   when they carry [COMPOUND_SYMBOL_ID] as a multi-component sentinel.
 *
 * ## Patch 19 additions
 *
 * - [resolutionSource]: optional diagnostic string tracking how this symbol was resolved.
 *   Examples: "function-word:it:default", "function-word:it:contracted-of",
 *   "unknown:casa". Empty string means resolved through the standard lexicon pipeline.
 * - [MatchType.FUNCTION_WORD]: new match type for symbols resolved by the Tier-0
 *   function-word fast-path. Distinct from EXACT (which means CSV/JSON lexicon match)
 *   so UI and tests can distinguish them.
 *
 * @param bciAvId          Official BCI-AV identifier (> 0). E.g. 12335.
 *                         Sentinel values UNKNOWN_SYMBOL_ID and COMPOUND_SYMBOL_ID
 *                         are also accepted (init validates accordingly).
 * @param name             English canonical name from bci_names.json (non-blank).
 * @param category         Semantic category tag (e.g. "action", "thing", "description").
 *                         Empty string if not present in dataset.
 * @param synsetId         WordNet 3.1 synset offset (from bci_blissnet.json), null if absent.
 * @param sourceWord       The surface word from user input that produced this lookup.
 * @param lemma            The lemma that matched (may differ from sourceWord after stemming).
 * @param matchType        How the match was found (see [MatchType]).
 * @param indicators       Per-token morphological indicators (e.g. ["plural", "past"]).
 *                         Empty list = no morphological tag on this token.
 * @param componentIds     Ordered BCI-AV IDs of the constituent base symbols
 *                         (only meaningful when [matchType] == [MatchType.COMPOUND]
 *                         or [MatchType.SEMANTIC]).
 * @param resolutionSource Diagnostic tag describing how this symbol was resolved.
 *                         Populated by the function-word fast-path and the unknown
 *                         fallback. Empty for standard lexicon lookups.
 */
data class BlissSymbol(
    val bciAvId:          Int,
    val name:             String,
    val category:         String        = "",
    val synsetId:         Long?         = null,
    val sourceWord:       String        = "",
    val lemma:            String        = "",
    val matchType:        MatchType     = MatchType.UNKNOWN,
    val indicators:       List<String>  = emptyList(),
    val componentIds:     List<Int>     = emptyList(),
    val resolutionSource: String        = ""
) {
    init {
        // Allow sentinel IDs for UNKNOWN, COMPOUND, SEMANTIC, and FUNCTION_WORD symbols;
        // all other symbols must carry a positive BCI-AV ID.
        val isSentinel = matchType == MatchType.UNKNOWN
                || matchType == MatchType.COMPOUND
                || (matchType == MatchType.SEMANTIC && bciAvId == COMPOUND_SYMBOL_ID)
        if (!isSentinel) {
            require(bciAvId > 0) { "bciAvId must be a positive integer, got: $bciAvId" }
        }
        require(name.isNotBlank()) { "name must not be blank for bciAvId=$bciAvId" }
    }

    // ── display helpers ───────────────────────────────────────────────

    /**
     * Human-readable gloss for display.
     * Truncated to [maxLen] characters for compact display.
     */
    fun gloss(maxLen: Int = Int.MAX_VALUE): String =
        if (name.length <= maxLen) name else name.take(maxLen - 1) + "\u2026"

    /** Convenience property — returns full [name]. */
    val gloss: String get() = name

    /** True when this symbol represents an unresolved/unknown token. */
    val isUnknown: Boolean get() = matchType == MatchType.UNKNOWN

    /** True when this symbol was synthesised by [BlissSemanticComposer] Stage C
     *  (orthographic pivot-split, legacy opt-in fallback). */
    val isCompound: Boolean get() = matchType == MatchType.COMPOUND

    /** True when this symbol was synthesised by [BlissSemanticComposer] Stage A or B
     *  (WordNet/BlissNet synset-grounded, BCI-conformant composition). */
    val isSemanticComposition: Boolean get() = matchType == MatchType.SEMANTIC

    /** True when this symbol was resolved by the Tier-0 function-word fast-path. */
    val isFunctionWord: Boolean get() = matchType == MatchType.FUNCTION_WORD

    /**
     * Returns a copy of this symbol with the given [newIndicators] attached.
     * Used by the async pipeline (tier 3b) to propagate per-token morphological
     * information without mutating the original instance.
     */
    fun withIndicators(newIndicators: List<String>): BlissSymbol =
        copy(indicators = newIndicators)

    // ── nested types ─────────────────────────────────────────────────────

    enum class MatchType {
        /** Surface token matched directly in the lexicon (CSV/JSON). */
        EXACT,
        /** Matched after lemmatisation or de-affixation. */
        LEMMA,
        /** Multi-word expression matched in the n-gram index. */
        NGRAM,
        /** No word match; a generic category symbol was used as fallback. */
        FALLBACK_CATEGORY,
        /**
         * Resolved by the Tier-0 function-word fast-path (Patch 19).
         * Covers conjunctions, prepositions, articles, contractions, and
         * other high-frequency grammatical words. Distinct from EXACT
         * (which means a CSV/JSON lexicon hit) so UI and tests can differentiate.
         */
        FUNCTION_WORD,
        /**
         * Composed from semantically related base symbols via WordNet/BlissNet
         * synset relations (Stage A or B of [BlissSemanticComposer]).
         * BCI-conformant composition — preferred over [COMPOUND].
         */
        SEMANTIC,
        /**
         * Multi-word orthographic pivot-split (Stage C of [BlissSemanticComposer],
         * legacy opt-in fallback).  Not BCI-conformant; retained for coverage.
         */
        COMPOUND,
        /** No match at all — rendered with the \"?\" symbol (BCI-AV 17729). */
        UNKNOWN
    }

    companion object {
        /** BCI-AV ID used as the universal \"unknown / question mark\" symbol. */
        const val UNKNOWN_SYMBOL_ID  = 17729

        /** BCI-AV ID for a generic spacer / blank placeholder. */
        const val BLANK_SYMBOL_ID    = 9011

        /**
         * Sentinel BCI-AV ID for a synthetic compound symbol produced by
         * [BlissSemanticComposer] Stage B or C.  Distinct from [UNKNOWN_SYMBOL_ID]
         * so the renderer can tell \"composed\" apart from \"not found at all\".
         */
        const val COMPOUND_SYMBOL_ID = -2
    }
}
