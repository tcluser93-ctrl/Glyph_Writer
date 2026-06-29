package com.blueapps.egyptianwriter.bliss

/**
 * Immutable value-object representing a single BCI-AV Bliss symbol.
 *
 * @param bciAvId   Official BCI-AV identifier (e.g. 12335)
 * @param name      English canonical name from bci_names.json
 * @param synsetId  WordNet 3.1 synset offset (from bci_blissnet.json), -1 if absent
 * @param sourceWord  The surface word from user input that produced this lookup
 * @param lemma       The lemma that matched (may differ from sourceWord after stemming)
 * @param matchType   How the match was found (EXACT, LEMMA, NGRAM, FALLBACK_CATEGORY, UNKNOWN)
 */
data class BlissSymbol(
    val bciAvId: Int,
    val name: String,
    val synsetId: Long = -1L,
    val sourceWord: String = "",
    val lemma: String = "",
    val matchType: MatchType = MatchType.UNKNOWN
) {
    enum class MatchType {
        EXACT,              // surface token matched directly in lexicon
        LEMMA,              // matched after lemmatisation
        NGRAM,              // multi-word expression matched
        FALLBACK_CATEGORY,  // no word match, used a generic category symbol
        UNKNOWN             // no match at all ("?" symbol, BCI-AV 17729)
    }

    companion object {
        /** BCI-AV ID used as the universal "unknown / question mark" symbol. */
        const val UNKNOWN_SYMBOL_ID = 17729

        /** BCI-AV ID for a generic spacer between words (⌀ — blank placeholder). */
        const val BLANK_SYMBOL_ID   = 9011
    }
}
