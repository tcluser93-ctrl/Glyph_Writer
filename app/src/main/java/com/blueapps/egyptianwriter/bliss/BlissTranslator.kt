package com.blueapps.egyptianwriter.bliss

import android.util.Log
import com.blueapps.egyptianwriter.bliss.BlissSymbol.MatchType
import java.util.Locale
import java.util.regex.Pattern

/**
 * Translates free natural-language text into a sequence of [BlissSymbol]s.
 *
 * ## Pipeline
 *
 *  1. **Normalise** — lowercase, collapse whitespace, strip punctuation
 *  2. **N-gram scan** — longest-match pass for multi-word expressions
 *  3. **Token loop** — for each remaining token:
 *       a. Exact surface lookup  → EXACT
 *       b. Lemma lookup          → LEMMA
 *       c. Prefix stripping (simple rule-based de-affix) → LEMMA
 *       d. Category fallback     → FALLBACK_CATEGORY  (not yet impl, reserved)
 *       e. Unknown symbol        → UNKNOWN
 *
 * The translator is stateless and thread-safe after construction.
 * It depends on a fully-loaded [BlissLookup] instance.
 *
 * @param lookup   Pre-loaded [BlissLookup]; must have [BlissLookup.isReady] == true.
 */
class BlissTranslator(private val lookup: BlissLookup) {

    /**
     * Translate [text] and return an ordered list of [BlissSymbol]s, one per
     * input token (or n-gram group). Never returns an empty list for non-empty input.
     */
    fun translate(text: String): List<BlissSymbol> {
        if (!lookup.isReady) {
            Log.w(TAG, "translate() called before lookup is ready")
            return emptyList()
        }
        if (text.isBlank()) return emptyList()

        val normalised = normalise(text)
        return resolveNgramsAndTokens(normalised)
    }

    // ── step 1 : normalise ────────────────────────────────────────────────────

    private fun normalise(raw: String): String {
        return raw
            .lowercase(Locale.ROOT)
            .replace(PUNCT_RE, " ")     // strip punctuation
            .replace(SPACE_RE, " ")     // collapse whitespace
            .trim()
    }

    // ── step 2+3 : greedy n-gram + per-token resolution ───────────────────────

    private fun resolveNgramsAndTokens(text: String): List<BlissSymbol> {
        val tokens = text.split(" ").filter { it.isNotBlank() }
        val result  = mutableListOf<BlissSymbol>()
        var i = 0

        while (i < tokens.size) {
            // try longest n-gram first (max 4 words)
            var matched = false
            for (len in minOf(MAX_NGRAM_LEN, tokens.size - i) downTo 2) {
                val phrase = tokens.subList(i, i + len).joinToString(" ")
                val id = lookup.lookupNgram(phrase)
                if (id != null) {
                    result += lookup.toSymbol(id, phrase, phrase, MatchType.NGRAM)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                result += resolveToken(tokens[i])
                i++
            }
        }
        return result
    }

    // ── step 3 : single-token resolution ─────────────────────────────────────

    private fun resolveToken(word: String): BlissSymbol {
        // 3a – exact surface match
        lookup.lookupSurface(word)?.let { id ->
            return lookup.toSymbol(id, word, word, MatchType.EXACT)
        }

        // 3b – direct lemma match (the CSV already contains lemmas)
        lookup.lookupLemma(word)?.let { id ->
            return lookup.toSymbol(id, word, word, MatchType.LEMMA)
        }

        // 3c – simple rule-based de-affixation  (language-agnostic heuristics)
        for (lemmaCandidate in simpleDeaffix(word)) {
            lookup.lookupSurface(lemmaCandidate)?.let { id ->
                return lookup.toSymbol(id, word, lemmaCandidate, MatchType.LEMMA)
            }
            lookup.lookupLemma(lemmaCandidate)?.let { id ->
                return lookup.toSymbol(id, word, lemmaCandidate, MatchType.LEMMA)
            }
        }

        // 3e – unknown
        return BlissSymbol(
            bciAvId    = BlissSymbol.UNKNOWN_SYMBOL_ID,
            name       = "unknown",
            sourceWord = word,
            lemma      = word,
            matchType  = MatchType.UNKNOWN
        )
    }

    // ── rule-based de-affixation (language-agnostic subset) ───────────────────

    /**
     * Returns a prioritised list of lemma candidates derived from [word] by
     * stripping common suffixes.  Returns empty if word is too short.
     */
    private fun simpleDeaffix(word: String): List<String> {
        if (word.length < 4) return emptyList()
        val candidates = mutableListOf<String>()

        // Italian / Spanish / Portuguese verb endings
        for (sfx in listOf("ando", "endo", "ando", "ato", "uto", "ito",
                           "are", "ere", "ire", "arse", "arsi")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }
        // English -ing / -ed / -s / -ly / -er / -est
        for (sfx in listOf("ing", "tion", "sion", "ness", "ment",
                           "ed", "er", "est", "ly", "s")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }
        // German -ung / -heit / -keit / -lich
        for (sfx in listOf("ung", "heit", "keit", "lich", "isch")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }

        return candidates.distinct()
    }

    // ── constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG           = "BlissTranslator"
        private const val MAX_NGRAM_LEN = 4
        private val PUNCT_RE = Pattern.compile("[^\\p{L}\\p{Nd}\\s'-]").toRegex()
        private val SPACE_RE  = Pattern.compile("\\s+").toRegex()
    }
}
