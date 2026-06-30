package com.blueapps.egyptianwriter.bliss

import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans

/**
 * Immutable value-object representing a single BCI-AV Bliss symbol.
 *
 * ## Enterprise changes (F1-01 / F1-02 / F1-11 / F1-12)
 *
 * - [category] added: semantic category string from bci_names.json
 *   (e.g. "action", "thing", "evaluation"). Required for FALLBACK_CATEGORY lookup.
 * - [synsetId] changed to `Long?` (was `Long = -1L`): idiomatic Kotlin nullable.
 * - [init] validates invariants: bciAvId must be positive; name must be non-blank.
 * - [displayLabel] now returns [CharSequence] backed by SpannableString so the
 *   BCI-AV id line renders at 70% size — works in any Android TextView context.
 *
 * @param bciAvId    Official BCI-AV identifier (> 0). E.g. 12335.
 * @param name       English canonical name from bci_names.json (non-blank).
 * @param category   Semantic category tag (e.g. "action", "thing", "description").
 *                   Empty string if not present in dataset.
 * @param synsetId   WordNet 3.1 synset offset (from bci_blissnet.json), null if absent.
 * @param sourceWord The surface word from user input that produced this lookup.
 * @param lemma      The lemma that matched (may differ from sourceWord after stemming).
 * @param matchType  How the match was found (see [MatchType]).
 */
data class BlissSymbol(
    val bciAvId:    Int,
    val name:       String,
    val category:   String     = "",
    val synsetId:   Long?      = null,
    val sourceWord: String     = "",
    val lemma:      String     = "",
    val matchType:  MatchType  = MatchType.UNKNOWN
) {
    init {
        require(bciAvId > 0) { "bciAvId must be a positive integer, got: $bciAvId" }
        require(name.isNotBlank()) { "name must not be blank for bciAvId=$bciAvId" }
    }

    // ── display helpers ──────────────────────────────────────────────────────

    /**
     * Human-readable gloss for display.
     * Truncated to [maxLen] characters for compact display.
     */
    fun gloss(maxLen: Int = Int.MAX_VALUE): String =
        if (name.length <= maxLen) name else name.take(maxLen - 1) + "\u2026"

    /** Convenience property — returns full [name]. */
    val gloss: String get() = name

    /**
     * Short label shown on UI chips as a [CharSequence] (SpannableString).
     *
     * Line 1: BCI-AV id at 70% text size  (e.g. "#12335")
     * Line 2: truncated name at 100%       (e.g. "camminare")
     *
     * Works in any Android TextView — no `\n` rendering issues (F1-11).
     */
    fun displayLabel(nameMax: Int = 14): CharSequence =
        buildSpannedString {
            inSpans(RelativeSizeSpan(0.7f)) { append("#$bciAvId") }
            append("\n")
            append(gloss(nameMax))
        }

    /** True when this symbol represents an unresolved/unknown token. */
    val isUnknown: Boolean get() = matchType == MatchType.UNKNOWN

    // ── nested types ─────────────────────────────────────────────────────────

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

        /** BCI-AV ID for a generic spacer / blank placeholder. */
        const val BLANK_SYMBOL_ID   = 9011
    }
}
