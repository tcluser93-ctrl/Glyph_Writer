package com.blueapps.egyptianwriter.bliss

/**
 * Immutable value-object representing a single BCI-AV Bliss symbol.
 *
 * @param bciAvId    Official BCI-AV identifier (e.g. 12335). Always a positive Int.
 * @param name       English canonical name from bci_names.json (e.g. "action, to")
 * @param synsetId   WordNet 3.1 synset offset (from bci_blissnet.json), -1 if absent
 * @param sourceWord The surface word from user input that produced this lookup
 * @param lemma      The lemma that matched (may differ from sourceWord after stemming)
 * @param matchType  How the match was found (see [MatchType])
 */
data class BlissSymbol(
    val bciAvId:    Int,
    val name:       String,
    val synsetId:   Long   = -1L,
    val sourceWord: String = "",
    val lemma:      String = "",
    val matchType:  MatchType = MatchType.UNKNOWN
) {
    /**
     * Human-readable gloss for display.
     * Alias of [name] — used by [BlissRenderer] and chip UIs.
     * Truncated to [maxLen] characters for compact display.
     */
    fun gloss(maxLen: Int = Int.MAX_VALUE): String =
        if (name.length <= maxLen) name else name.take(maxLen - 1) + "…"

    /**
     * Convenience property for [BlissRenderer] compatibility — returns full [name].
     * Prefer [gloss] with an explicit maxLen when truncation is needed.
     */
    val gloss: String get() = name

    /**
     * Short label shown on UI chips: "#12335\ncamminare".
     * Capped at [nameMax] chars to fit fixed-width cells.
     */
    fun displayLabel(nameMax: Int = 14): String = "#$bciAvId\n${gloss(nameMax)}"

    /** True when this symbol represents an unresolved/unknown token. */
    val isUnknown: Boolean get() = matchType == MatchType.UNKNOWN

    enum class MatchType {
        /** Surface token matched directly in the lexicon. */
        EXACT,
        /** Matched after lemmatisation or de-affixation. */
        LEMMA,
        /** Multi-word expression matched in the n-gram index. */
        NGRAM,
        /** No word match; a generic category symbol was used as fallback. */
        FALLBACK_CATEGORY,
        /** No match at all — rendered with the \"?\" symbol (BCI-AV 17729). */
        UNKNOWN
    }

    companion object {
        /** BCI-AV ID used as the universal \"unknown / question mark\" symbol. */
        const val UNKNOWN_SYMBOL_ID = 17729

        /** BCI-AV ID for a generic spacer / blank placeholder (⌀). */
        const val BLANK_SYMBOL_ID   = 9011
    }
}
