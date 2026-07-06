package com.blueapps.egyptianwriter.bliss

/**
 * Result of a Morfologik morphological analysis for a single surface word.
 *
 * @param lemma           Canonical base form returned by the FSA dictionary.
 * @param rawTag          Raw Morfologik tag string (e.g. "VER:pres+1s"), null if absent.
 * @param blissIndicators Bliss indicator constants derived from [rawTag] by [MorfologikTagMapper].
 */
data class LemmaAnalysis(
    val lemma: String,
    val rawTag: String? = null,
    val blissIndicators: List<String> = emptyList()
)
