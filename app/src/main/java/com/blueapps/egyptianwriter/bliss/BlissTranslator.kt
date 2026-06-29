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
 *  2. **N-gram scan** — longest-match pass for multi-word expressions (max 4 words)
 *  3. **Token loop** — for each remaining token:
 *       a. Exact surface lookup                          → EXACT
 *       b. Plain lemma lookup                            → LEMMA
 *       c. POS-aware lemma lookup (heuristic POS tag)   → LEMMA
 *       d. Rule-based de-affixation candidates          → LEMMA
 *       e. Unknown symbol                               → UNKNOWN
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

    private fun normalise(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(PUNCT_RE, " ")
            .replace(SPACE_RE, " ")
            .trim()

    // ── step 2+3 : greedy n-gram + per-token resolution ───────────────────────

    private fun resolveNgramsAndTokens(text: String): List<BlissSymbol> {
        val tokens = text.split(" ").filter { it.isNotBlank() }
        val result  = mutableListOf<BlissSymbol>()
        var i = 0

        while (i < tokens.size) {
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

        // 3b – plain lemma match
        lookup.lookupLemma(word)?.let { id ->
            return lookup.toSymbol(id, word, word, MatchType.LEMMA)
        }

        // 3c – POS-aware lemma lookup with heuristic POS guessing
        val guessedPos = heuristicPos(word)
        if (guessedPos != null) {
            lookup.lookupLemmaPos(word, guessedPos)?.let { id ->
                return lookup.toSymbol(id, word, word, MatchType.LEMMA)
            }
        }

        // 3d – rule-based de-affixation candidates
        for (candidate in simpleDeaffix(word)) {
            // try candidate as surface first, then as lemma (plain + POS)
            lookup.lookupSurface(candidate)?.let { id ->
                return lookup.toSymbol(id, word, candidate, MatchType.LEMMA)
            }
            lookup.lookupLemma(candidate)?.let { id ->
                return lookup.toSymbol(id, word, candidate, MatchType.LEMMA)
            }
            if (guessedPos != null) {
                lookup.lookupLemmaPos(candidate, guessedPos)?.let { id ->
                    return lookup.toSymbol(id, word, candidate, MatchType.LEMMA)
                }
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

    // ── heuristic POS tagger (rule-based, language-agnostic subset) ───────────

    /**
     * Returns a rough POS tag for [word] using suffix heuristics.
     * Covers Italian, English, German, French, Spanish.
     * Returns null when no confident guess is possible.
     *
     * Tag set: V=verb  N=noun  A=adjective  R=adverb
     */
    private fun heuristicPos(word: String): String? {
        if (word.length < 4) return null
        return when {
            // Italian / Spanish verb infinitives and gerunds
            word.endsWith("are")  || word.endsWith("ere")  || word.endsWith("ire")  -> "V"
            word.endsWith("ando") || word.endsWith("endo")                           -> "V"
            word.endsWith("ato")  || word.endsWith("uto")  || word.endsWith("ito")  -> "V"
            // English verb
            word.endsWith("ing")  || word.endsWith("tion") || word.endsWith("sion") -> "N" // -tion/-sion → noun
            word.endsWith("ed")                                                       -> "V"
            // Adjective suffixes (multilingual)
            word.endsWith("oso")  || word.endsWith("osa")  ||
            word.endsWith("ous")  || word.endsWith("ful")  || word.endsWith("less") ||
            word.endsWith("lich") || word.endsWith("isch") || word.endsWith("ible") ||
            word.endsWith("able")                                                     -> "A"
            // Adverb
            word.endsWith("mente")|| word.endsWith("ment") || word.endsWith("ly")   -> "R"
            // Noun suffixes
            word.endsWith("zione")|| word.endsWith("ità")  || word.endsWith("ness") ||
            word.endsWith("heit") || word.endsWith("keit") || word.endsWith("ung")  ||
            word.endsWith("ismo") || word.endsWith("ista")                            -> "N"
            else -> null
        }
    }

    // ── rule-based de-affixation ───────────────────────────────────────────────

    /**
     * Returns a prioritised list of lemma candidates derived from [word] by
     * stripping common suffixes. Returns empty if word is too short.
     *
     * Extended for Italian: handles irregular verb stems, reflexive -si/-rsi,
     * -zione/-ità/-ismo noun suffixes, and common adjective endings.
     */
    private fun simpleDeaffix(word: String): List<String> {
        if (word.length < 4) return emptyList()
        val candidates = mutableListOf<String>()

        // ── Italian ──
        // Reflexive / pronominal verbs
        for (sfx in listOf("arsi", "ersi", "irsi", "rsi", "si")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2) {
                val stem = word.dropLast(sfx.length)
                candidates += stem + "re"   // camminare ← camminare + si stripped
                candidates += stem
            }
        }
        // Infinitives and participles
        for (sfx in listOf("ando", "endo", "ato", "uto", "ito",
                           "are", "ere", "ire",
                           "azione", "zione", "ità", "ismo", "ista")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }
        // Plural / gender endings (it/es)
        for (sfx in listOf("osi", "ose", "asi", "ase", "i", "e", "a")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 3)
                candidates += word.dropLast(sfx.length) + "o"
        }

        // ── English ──
        for (sfx in listOf("ing", "tion", "sion", "ness", "ment",
                           "ed", "er", "est", "ly", "s")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }

        // ── German ──
        for (sfx in listOf("ung", "heit", "keit", "lich", "isch",
                           "en", "er", "em", "es")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }

        // ── French ──
        for (sfx in listOf("ment", "tion", "eur", "euse", "eux", "euse",
                           "er", "ir", "re")) {
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
