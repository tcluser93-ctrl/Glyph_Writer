package com.blueapps.egyptianwriter.bliss

import java.util.Locale

/**
 * Maps Morfologik / LanguageTool morphological raw tags to Bliss indicator constants.
 *
 * Design principles:
 * - Conservative: only emit an indicator when the tag signal is unambiguous.
 * - Language-agnostic at the string level (lowercased substring matching).
 * - Returns an empty list rather than guessing when confidence is low.
 *
 * Supported mappings:
 * - plural suffix / "+pl" / ":p"  → [BlissTranslator.INDICATOR_PLURAL]
 * - past / pst / imperf / ppast   → [BlissTranslator.INDICATOR_PAST]
 * - fut / future                  → [BlissTranslator.INDICATOR_FUTURE]
 */
object MorfologikTagMapper {

    fun toBlissIndicators(rawTag: String?): List<String> {
        if (rawTag.isNullOrBlank()) return emptyList()
        val t = rawTag.lowercase(Locale.ROOT)

        val out = linkedSetOf<String>()
        if (looksPlural(t))  out += BlissTranslator.INDICATOR_PLURAL
        if (looksPast(t))    out += BlissTranslator.INDICATOR_PAST
        if (looksFuture(t))  out += BlissTranslator.INDICATOR_FUTURE
        return out.toList()
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun looksPlural(tag: String): Boolean =
        tag.contains("plural") ||
        tag.contains("+pl")    ||
        tag.contains(":pl")    ||
        (tag.contains(":p") && !tag.contains(":past") && !tag.contains(":pres"))

    private fun looksPast(tag: String): Boolean =
        tag.contains("past")   ||
        tag.contains("pst")    ||
        tag.contains("imperf") ||
        tag.contains("ppast")  ||
        (tag.contains("part") && (tag.contains("pp") || tag.contains("partcp")))

    private fun looksFuture(tag: String): Boolean =
        tag.contains("fut") ||
        tag.contains("future")
}
