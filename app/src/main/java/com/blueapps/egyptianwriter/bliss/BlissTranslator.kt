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
 *  4. **Indicator pass** — detectIndicators() scans the original token list
 *     and calls attachIndicators() to tag each symbol with grammatical
 *     indicators (plural, past, future). Zero overhead on the hot path.
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
        val tokens     = normalised.split(" ").filter { it.isNotBlank() }
        val symbols    = resolveNgramsAndTokens(normalised)

        // Step 4 — indicator pass (runs after main pipeline, O(n) extra scan)
        val indicators = detectIndicators(tokens)
        return attachIndicators(symbols, indicators)
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

    // ── step 4a : indicator detection ────────────────────────────────────────

    /**
     * Scans [tokens] (already normalised, lowercase) and returns a set of
     * grammatical indicators detected in the sentence as a whole.
     *
     * Covered indicators:
     * - **plural**  : morphological suffixes + quantifier keywords (multilingual)
     * - **past**    : participial suffixes + auxiliary verbs (it/en/de/fr/es)
     * - **future**  : modal + infinitive constructions (it/en/de/fr/es)
     *
     * Returns a [Set]<[String]> using the canonical indicator names understood
     * by [BlissGlyphXBuilder]: "plural", "past", "future".
     */
    internal fun detectIndicators(tokens: List<String>): Set<String> {
        val found = mutableSetOf<String>()
        val sentence = tokens.joinToString(" ")

        // ── PLURAL ────────────────────────────────────────────────────────────
        // Quantifier keywords (language-agnostic, multi-word safe)
        val pluralKeywords = setOf(
            // Italian
            "alcuni", "alcune", "pochi", "poche", "molti", "molte",
            "tanti", "tante", "diversi", "diverse", "parecchi", "parecchie",
            "tutti", "tutte", "certi", "certe",
            // English
            "many", "several", "few", "all", "both", "various", "multiple",
            "numerous", "these", "those",
            // Spanish
            "muchos", "muchas", "varios", "varias", "algunos", "algunas",
            "tantos", "tantas", "todos", "todas",
            // German
            "viele", "einige", "manche", "mehrere", "alle", "wenige",
            // French
            "plusieurs", "certains", "certaines", "beaucoup", "tous", "toutes"
        )
        if (tokens.any { it in pluralKeywords }) {
            found += INDICATOR_PLURAL
        }
        // Morphological plural: token ends with common plural markers
        // Only triggers when at least 2 content tokens carry the suffix
        val pluralSuffixes = listOf("i", "e", "s", "es", "en", "ren", "aux", "x")
        val contentTokens = tokens.filter { it.length >= 4 }
        val pluralSuffixCount = contentTokens.count { tok ->
            pluralSuffixes.any { sfx -> tok.endsWith(sfx) && tok.length > sfx.length + 2 }
        }
        if (pluralSuffixCount >= 2) {
            found += INDICATOR_PLURAL
        }

        // ── PAST ──────────────────────────────────────────────────────────────
        // Italian auxiliaries (ha, hanno, aveva, ebbe, è + participle pattern)
        if (PAST_IT_AUX_RE.containsMatchIn(sentence)) found += INDICATOR_PAST
        // Italian past participle suffix: -ato/-ito/-uto standalone
        if (tokens.any { PAST_IT_PARTICIPLE_RE.matches(it) }) found += INDICATOR_PAST
        // English: had/has/have + token ending -ed
        if (PAST_EN_RE.containsMatchIn(sentence)) found += INDICATOR_PAST
        // French: avait/avaient/avais/a + participle (-é/-i/-u)
        if (PAST_FR_RE.containsMatchIn(sentence)) found += INDICATOR_PAST
        // German: hatte/hatten/hat + participle (ge-…-t/ge-…-en)
        if (PAST_DE_RE.containsMatchIn(sentence)) found += INDICATOR_PAST
        // Spanish: tuvo/tuvo/había + participle (-ado/-ido)
        if (PAST_ES_RE.containsMatchIn(sentence)) found += INDICATOR_PAST

        // ── FUTURE ────────────────────────────────────────────────────────────
        // English: will/shall + bare infinitive
        if (FUTURE_EN_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE
        // Italian: andrà/verrà/farà + infinitive OR explicit "futuro"
        if (FUTURE_IT_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE
        // Spanish: irá/irán/será/serán + infinitive, or -ará/-erá/-irá suffix
        if (FUTURE_ES_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE
        // German: wird/werden + infinitive
        if (FUTURE_DE_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE
        // French: ira/sera/aura + infinitive, or -era/-ira suffix
        if (FUTURE_FR_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE

        return found
    }

    // ── step 4b : attach indicators to symbols ────────────────────────────────

    /**
     * Calls [BlissSymbol.withIndicators] on every symbol that is not UNKNOWN,
     * injecting the sentence-level [indicators] set. Returns a new list;
     * original symbols are not mutated (data class copy semantics).
     *
     * UNKNOWN symbols are intentionally left untagged: they have no visual
     * representation and adding indicators would be misleading.
     */
    internal fun attachIndicators(
        symbols: List<BlissSymbol>,
        indicators: Set<String>
    ): List<BlissSymbol> {
        if (indicators.isEmpty()) return symbols
        return symbols.map { sym ->
            if (sym.matchType != MatchType.UNKNOWN && indicators.isNotEmpty()) {
                sym.withIndicators(indicators.toList())
            } else {
                sym
            }
        }
    }

    // ── heuristic POS tagger ──────────────────────────────────────────────────

    private fun heuristicPos(word: String): String? {
        if (word.length < 4) return null
        return when {
            word.endsWith("are")  || word.endsWith("ere")  || word.endsWith("ire")  -> "V"
            word.endsWith("ando") || word.endsWith("endo")                           -> "V"
            word.endsWith("ato")  || word.endsWith("uto")  || word.endsWith("ito")  -> "V"
            word.endsWith("ing")  || word.endsWith("tion") || word.endsWith("sion") -> "N"
            word.endsWith("ed")                                                       -> "V"
            word.endsWith("oso")  || word.endsWith("osa")  ||
            word.endsWith("ous")  || word.endsWith("ful")  || word.endsWith("less") ||
            word.endsWith("lich") || word.endsWith("isch") || word.endsWith("ible") ||
            word.endsWith("able")                                                     -> "A"
            word.endsWith("mente")|| word.endsWith("ment") || word.endsWith("ly")   -> "R"
            word.endsWith("zione")|| word.endsWith("ità")  || word.endsWith("ness") ||
            word.endsWith("heit") || word.endsWith("keit") || word.endsWith("ung")  ||
            word.endsWith("ismo") || word.endsWith("ista")                            -> "N"
            else -> null
        }
    }

    // ── rule-based de-affixation ───────────────────────────────────────────────

    private fun simpleDeaffix(word: String): List<String> {
        if (word.length < 4) return emptyList()
        val candidates = mutableListOf<String>()

        for (sfx in listOf("arsi", "ersi", "irsi", "rsi", "si")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2) {
                val stem = word.dropLast(sfx.length)
                candidates += stem + "re"
                candidates += stem
            }
        }
        for (sfx in listOf("ando", "endo", "ato", "uto", "ito",
                           "are", "ere", "ire",
                           "azione", "zione", "ità", "ismo", "ista")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }
        for (sfx in listOf("osi", "ose", "asi", "ase", "i", "e", "a")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 3)
                candidates += word.dropLast(sfx.length) + "o"
        }
        for (sfx in listOf("ing", "tion", "sion", "ness", "ment",
                           "ed", "er", "est", "ly", "s")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }
        for (sfx in listOf("ung", "heit", "keit", "lich", "isch",
                           "en", "er", "em", "es")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2)
                candidates += word.dropLast(sfx.length)
        }
        for (sfx in listOf("ment", "tion", "eur", "euse", "eux",
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

        const val INDICATOR_PLURAL = "plural"
        const val INDICATOR_PAST   = "past"
        const val INDICATOR_FUTURE = "future"

        private val PUNCT_RE = Pattern.compile("[^\\p{L}\\p{Nd}\\s'-]").toRegex()
        private val SPACE_RE  = Pattern.compile("\\s+").toRegex()

        // ── PAST regexes ──────────────────────────────────────────────────────
        // Italian: ha/hanno/aveva/avevano/è/sono + any word boundary
        private val PAST_IT_AUX_RE =
            Regex("\\b(ha|hanno|aveva|avevano|ebbe|ebbero|è stato|sono stati|ho|abbiamo)\\b")
        // Italian past participle standalone: ends -ato/-ito/-uto, length >= 5
        private val PAST_IT_PARTICIPLE_RE =
            Regex("[a-z]{3,}(ato|ito|uto)")
        // English: had/has/have/was/were ... (-ed)
        private val PAST_EN_RE =
            Regex("\\b(had|has|have|was|were|did)\\b.*\\b\\w+ed\\b")
        // French: avait/avaient/a/ont ... (-é/-i/-u)
        private val PAST_FR_RE =
            Regex("\\b(avait|avaient|avais|a|ont|est|sont)\\b.*\\b\\w+(é|i|u)\\b")
        // German: hatte/hatten/hat/ist/sind + ge-stem
        private val PAST_DE_RE =
            Regex("\\b(hatte|hatten|hat|ist|sind|wurde|wurden)\\b.*\\bge\\w+\\b")
        // Spanish: tuvo/tuvo/había/-ado/-ido
        private val PAST_ES_RE =
            Regex("\\b(tuvo|tuvieron|había|habían|ha|han|fue|fueron)\\b.*\\b\\w+(ado|ido)\\b")

        // ── FUTURE regexes ────────────────────────────────────────────────────
        private val FUTURE_EN_RE =
            Regex("\\b(will|shall|going to|won't|shan't)\\b")
        private val FUTURE_IT_RE =
            Regex("\\b(andrà|andranno|verrà|verranno|sarà|saranno|farà|faranno|" +
                       "dovrà|dovranno|potrà|potranno|vorrà|vorranno|" +
                       "\\w+(erà|irà|arà|eranno|iranno|aranno))\\b")
        private val FUTURE_ES_RE =
            Regex("\\b(irá|irán|será|serán|hará|harán|tendrá|tendrán|" +
                       "\\w+(ará|erá|irá|arán|erán|irán))\\b")
        private val FUTURE_DE_RE =
            Regex("\\b(wird|werden|werde|wirst|werdet)\\b")
        private val FUTURE_FR_RE =
            Regex("\\b(ira|iront|sera|seront|aura|auront|fera|feront|" +
                       "\\w+(era|ira|eras|iras|erons|irons|erez|irez|eront|iront))\\b")
    }
}
