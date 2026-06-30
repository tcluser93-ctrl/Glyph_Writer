package com.blueapps.egyptianwriter.bliss

import android.util.Log
import com.blueapps.egyptianwriter.bliss.BlissSymbol.MatchType
import java.util.Locale
import java.util.regex.Pattern

/**
 * Translates free natural-language text into a sequence of [BlissSymbol]s.
 *
 * ## Sync pipeline (translate)
 *
 *  1. **Normalise** — lowercase, collapse whitespace, strip punctuation
 *  2. **N-gram scan** — longest-match (max 4 words)
 *  3. **Token loop** (per token):
 *       a. Exact surface lookup                        → EXACT
 *       b. Plain lemma lookup                          → LEMMA
 *       c. POS-aware lemma lookup                      → LEMMA
 *       d. Rule-based de-affixation candidates         → LEMMA
 *       e. Unknown                                     → UNKNOWN
 *  4. **Indicator pass** — plural / past / future tagging
 *
 * ## Async pipeline (translateAsync) — adds Morfologik tier
 *
 *  Same as above, but step 3 inserts:
 *       c2. MorfologikLemmatizer FSA lookup (IT/EN/DE) → LEMMA
 *  between heuristic POS (3c) and rule-based de-affixation (3d).
 *
 * The translator is stateless and thread-safe after construction.
 *
 * @param lookup        Pre-loaded [BlissLookup] (must have isReady == true).
 * @param morfologik    Optional [MorfologikLemmatizer]; if null the Morfologik
 *                      tier is silently skipped (graceful degradation).
 */
class BlissTranslator(
    private val lookup:     BlissLookup,
    private val morfologik: MorfologikLemmatizer? = null
) {

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Synchronous translation.  Rule-based only (no Morfologik).
     * Safe to call from any thread after [BlissLookup.isReady] == true.
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
        return attachIndicators(symbols, detectIndicators(tokens))
    }

    /**
     * Suspend translation.  Adds **Morfologik tier** (tier 3c2) between the
     * heuristic POS guess and the rule-based de-affixation.
     *
     * Must be called from a coroutine (typically [BlissViewModel.translate]).
     * The Morfologik FSA lookup runs on [Dispatchers.IO] inside
     * [MorfologikLemmatizer.lemmatize].
     */
    suspend fun translateAsync(text: String): List<BlissSymbol> {
        if (!lookup.isReady) {
            Log.w(TAG, "translateAsync() called before lookup is ready")
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        val normalised = normalise(text)
        val tokens     = normalised.split(" ").filter { it.isNotBlank() }
        val lang       = lookup.currentLang ?: "en"
        val symbols    = resolveNgramsAndTokensSuspend(normalised, lang)
        return attachIndicators(symbols, detectIndicators(tokens))
    }

    // ── step 1 : normalise ────────────────────────────────────────────────────

    private fun normalise(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(PUNCT_RE, " ")
            .replace(SPACE_RE, " ")
            .trim()

    // ── step 2+3 : greedy n-gram + per-token (sync) ───────────────────────────

    private fun resolveNgramsAndTokens(text: String): List<BlissSymbol> {
        val tokens = text.split(" ").filter { it.isNotBlank() }
        val result = mutableListOf<BlissSymbol>()
        var i = 0
        while (i < tokens.size) {
            var matched = false
            for (len in minOf(MAX_NGRAM_LEN, tokens.size - i) downTo 2) {
                val phrase = tokens.subList(i, i + len).joinToString(" ")
                lookup.lookupNgram(phrase)?.let { id ->
                    result += lookup.toSymbol(id, phrase, phrase, MatchType.NGRAM)
                    i += len; matched = true; return@let
                }
                if (matched) break
            }
            if (!matched) { result += resolveToken(tokens[i]); i++ }
        }
        return result
    }

    // ── step 2+3 : greedy n-gram + per-token (suspend, with Morfologik) ───────

    private suspend fun resolveNgramsAndTokensSuspend(
        text: String,
        lang: String
    ): List<BlissSymbol> {
        val tokens = text.split(" ").filter { it.isNotBlank() }
        val result = mutableListOf<BlissSymbol>()
        var i = 0
        while (i < tokens.size) {
            var matched = false
            for (len in minOf(MAX_NGRAM_LEN, tokens.size - i) downTo 2) {
                val phrase = tokens.subList(i, i + len).joinToString(" ")
                lookup.lookupNgram(phrase)?.let { id ->
                    result += lookup.toSymbol(id, phrase, phrase, MatchType.NGRAM)
                    i += len; matched = true; return@let
                }
                if (matched) break
            }
            if (!matched) {
                result += resolveTokenSuspend(tokens[i], lang)
                i++
            }
        }
        return result
    }

    // ── step 3 : single-token resolution (sync) ───────────────────────────────

    private fun resolveToken(word: String): BlissSymbol {
        lookup.lookupSurface(word)?.let { return lookup.toSymbol(it, word, word, MatchType.EXACT) }
        lookup.lookupLemma(word)?.let   { return lookup.toSymbol(it, word, word, MatchType.LEMMA) }
        val gPos = heuristicPos(word)
        if (gPos != null) lookup.lookupLemmaPos(word, gPos)?.let {
            return lookup.toSymbol(it, word, word, MatchType.LEMMA)
        }
        for (candidate in simpleDeaffix(word)) {
            lookup.lookupSurface(candidate)?.let { return lookup.toSymbol(it, word, candidate, MatchType.LEMMA) }
            lookup.lookupLemma(candidate)?.let   { return lookup.toSymbol(it, word, candidate, MatchType.LEMMA) }
            if (gPos != null) lookup.lookupLemmaPos(candidate, gPos)?.let {
                return lookup.toSymbol(it, word, candidate, MatchType.LEMMA)
            }
        }
        return unknownSymbol(word)
    }

    // ── step 3 : single-token resolution (suspend, adds Morfologik tier) ──────

    private suspend fun resolveTokenSuspend(word: String, lang: String): BlissSymbol {
        // Tier 3a — exact surface
        lookup.lookupSurface(word)?.let { return lookup.toSymbol(it, word, word, MatchType.EXACT) }
        // Tier 3b — plain lemma
        lookup.lookupLemma(word)?.let   { return lookup.toSymbol(it, word, word, MatchType.LEMMA) }
        // Tier 3c — POS-aware heuristic
        val gPos = heuristicPos(word)
        if (gPos != null) lookup.lookupLemmaPos(word, gPos)?.let {
            return lookup.toSymbol(it, word, word, MatchType.LEMMA)
        }
        // Tier 3c2 — MORFOLOGIK FSA (IT/EN/DE; no-op if dict absent)
        morfologik?.lemmatize(word, lang)?.forEach { lemma ->
            lookup.lookupSurface(lemma)?.let { return lookup.toSymbol(it, word, lemma, MatchType.LEMMA) }
            lookup.lookupLemma(lemma)?.let   { return lookup.toSymbol(it, word, lemma, MatchType.LEMMA) }
            if (gPos != null) lookup.lookupLemmaPos(lemma, gPos)?.let {
                return lookup.toSymbol(it, word, lemma, MatchType.LEMMA)
            }
        }
        // Tier 3d — rule-based de-affixation
        for (candidate in simpleDeaffix(word)) {
            lookup.lookupSurface(candidate)?.let { return lookup.toSymbol(it, word, candidate, MatchType.LEMMA) }
            lookup.lookupLemma(candidate)?.let   { return lookup.toSymbol(it, word, candidate, MatchType.LEMMA) }
            if (gPos != null) lookup.lookupLemmaPos(candidate, gPos)?.let {
                return lookup.toSymbol(it, word, candidate, MatchType.LEMMA)
            }
        }
        // Tier 3e — Room FTS4 exact (suspend DB call, catches words added later)
        lookup.lookupSurfaceDb(word)?.let { return lookup.toSymbol(it, word, word, MatchType.EXACT) }
        // Tier 3f — UNKNOWN
        return unknownSymbol(word)
    }

    private fun unknownSymbol(word: String) = BlissSymbol(
        bciAvId    = BlissSymbol.UNKNOWN_SYMBOL_ID,
        name       = "unknown",
        sourceWord = word,
        lemma      = word,
        matchType  = MatchType.UNKNOWN
    )

    // ── step 4a : indicator detection ────────────────────────────────────────

    internal fun detectIndicators(tokens: List<String>): Set<String> {
        val found = mutableSetOf<String>()
        val sentence = tokens.joinToString(" ")

        val pluralKeywords = setOf(
            "alcuni", "alcune", "pochi", "poche", "molti", "molte",
            "tanti", "tante", "diversi", "diverse", "parecchi", "parecchie",
            "tutti", "tutte", "certi", "certe",
            "many", "several", "few", "all", "both", "various", "multiple",
            "numerous", "these", "those",
            "muchos", "muchas", "varios", "varias", "algunos", "algunas",
            "tantos", "tantas", "todos", "todas",
            "viele", "einige", "manche", "mehrere", "alle", "wenige",
            "plusieurs", "certains", "certaines", "beaucoup", "tous", "toutes"
        )
        if (tokens.any { it in pluralKeywords }) found += INDICATOR_PLURAL

        val pluralSuffixes = listOf("i", "e", "s", "es", "en", "ren", "aux", "x")
        val contentTokens = tokens.filter { it.length >= 4 }
        val pluralSuffixCount = contentTokens.count { tok ->
            pluralSuffixes.any { sfx -> tok.endsWith(sfx) && tok.length > sfx.length + 2 }
        }
        if (pluralSuffixCount >= 2) found += INDICATOR_PLURAL

        if (PAST_IT_AUX_RE.containsMatchIn(sentence))        found += INDICATOR_PAST
        if (tokens.any { PAST_IT_PARTICIPLE_RE.matches(it) }) found += INDICATOR_PAST
        if (PAST_EN_RE.containsMatchIn(sentence))             found += INDICATOR_PAST
        if (PAST_FR_RE.containsMatchIn(sentence))             found += INDICATOR_PAST
        if (PAST_DE_RE.containsMatchIn(sentence))             found += INDICATOR_PAST
        if (PAST_ES_RE.containsMatchIn(sentence))             found += INDICATOR_PAST

        if (FUTURE_EN_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE
        if (FUTURE_IT_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE
        if (FUTURE_ES_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE
        if (FUTURE_DE_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE
        if (FUTURE_FR_RE.containsMatchIn(sentence)) found += INDICATOR_FUTURE

        return found
    }

    internal fun attachIndicators(
        symbols: List<BlissSymbol>,
        indicators: Set<String>
    ): List<BlissSymbol> {
        if (indicators.isEmpty()) return symbols
        return symbols.map { sym ->
            if (sym.matchType != MatchType.UNKNOWN) sym.withIndicators(indicators.toList())
            else sym
        }
    }

    // ── heuristic POS ────────────────────────────────────────────────────────

    private fun heuristicPos(word: String): String? {
        if (word.length < 4) return null
        return when {
            word.endsWith("are")  || word.endsWith("ere") || word.endsWith("ire")   -> "V"
            word.endsWith("ando") || word.endsWith("endo")                           -> "V"
            word.endsWith("ato")  || word.endsWith("uto") || word.endsWith("ito")   -> "V"
            word.endsWith("ing")  || word.endsWith("tion")|| word.endsWith("sion")  -> "N"
            word.endsWith("ed")                                                       -> "V"
            word.endsWith("oso")  || word.endsWith("osa") ||
            word.endsWith("ous")  || word.endsWith("ful") || word.endsWith("less") ||
            word.endsWith("lich") || word.endsWith("isch")|| word.endsWith("ible") ||
            word.endsWith("able")                                                     -> "A"
            word.endsWith("mente")|| word.endsWith("ment")|| word.endsWith("ly")    -> "R"
            word.endsWith("zione")|| word.endsWith("ità") || word.endsWith("ness") ||
            word.endsWith("heit") || word.endsWith("keit")|| word.endsWith("ung")  ||
            word.endsWith("ismo") || word.endsWith("ista")                            -> "N"
            else -> null
        }
    }

    // ── rule-based de-affixation ──────────────────────────────────────────────

    private fun simpleDeaffix(word: String): List<String> {
        if (word.length < 4) return emptyList()
        val candidates = mutableListOf<String>()
        for (sfx in listOf("arsi", "ersi", "irsi", "rsi", "si")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 2) {
                candidates += word.dropLast(sfx.length) + "re"
                candidates += word.dropLast(sfx.length)
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

    companion object {
        private const val TAG           = "BlissTranslator"
        private const val MAX_NGRAM_LEN = 4

        const val INDICATOR_PLURAL = "plural"
        const val INDICATOR_PAST   = "past"
        const val INDICATOR_FUTURE = "future"

        private val PUNCT_RE = Pattern.compile("[^\\p{L}\\p{Nd}\\s'-]").toRegex()
        private val SPACE_RE = Pattern.compile("\\s+").toRegex()

        private val PAST_IT_AUX_RE =
            Regex("\\b(ha|hanno|aveva|avevano|ebbe|ebbero|è stato|sono stati|ho|abbiamo)\\b")
        private val PAST_IT_PARTICIPLE_RE = Regex("[a-z]{3,}(ato|ito|uto)")
        private val PAST_EN_RE =
            Regex("\\b(had|has|have|was|were|did)\\b.*\\b\\w+ed\\b")
        private val PAST_FR_RE =
            Regex("\\b(avait|avaient|avais|a|ont|est|sont)\\b.*\\b\\w+(é|i|u)\\b")
        private val PAST_DE_RE =
            Regex("\\b(hatte|hatten|hat|ist|sind|wurde|wurden)\\b.*\\bge\\w+\\b")
        private val PAST_ES_RE =
            Regex("\\b(tuvo|tuvieron|había|habían|ha|han|fue|fueron)\\b.*\\b\\w+(ado|ido)\\b")

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
