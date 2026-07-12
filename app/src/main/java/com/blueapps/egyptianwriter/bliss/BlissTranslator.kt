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
 *  4. **Indicator pass** — plural / past / future tagging (sentence-level)
 *
 * ## Async pipeline (translateAsync) — Morfologik-first
 *
 *  Same as above, but step 3 uses the Morfologik-first order:
 *       3-0. Function-word fast-path (tier 0)           → EXACT  ← PATCH 18
 *       3a.  Exact surface lookup                       → EXACT
 *       3b.  Morfologik FSA → lemma+tag → indicators    → LEMMA  ← PRIMARY
 *       3c.  Plain lemma lookup                         → LEMMA
 *       3d.  POS-aware heuristic + CSV                  → LEMMA
 *       3e.  Rule-based de-affixation                   → LEMMA
 *       3f.  Room FTS4 exact                            → EXACT
 *       3g.  Semantic composition                       → SEMANTIC / per-component ← PATCH 7
 *       3h.  UNKNOWN
 *
 * ## Patch 18 — Tier-0 function-word fast-path
 *
 *  Short function words (conjunctions, prepositions, negators) of length ≤ 4
 *  are resolved against [FUNCTION_WORDS] before any CSV / Morfologik lookup.
 *  The map covers the 30 most common function words across IT/EN/DE/FR/ES.
 *  BCI-AV IDs are taken from the official BCI-AV symbol list:
 *
 *    12335 = and       12343 = or        12346 = but       12344 = if
 *    12348 = because   14951 = with      14960 = for       25564 = to
 *    25563 = of        25565 = in        17720 = not       17744 = no
 *    12347 = when      12349 = so/then   14941 = from      14945 = by
 *    14942 = at        14943 = on
 *
 *  Per-language aliases map to the canonical English BCI-AV entry.
 *
 * @param lookup        Pre-loaded [BlissLookup] (must have isReady == true).
 * @param morfologik    Optional [MorfologikLemmatizer]; if null the Morfologik
 *                      tier is silently skipped (graceful degradation).
 * @param composer      Optional [BlissSemanticComposer]; if null tier 3g is
 *                      silently skipped.  Pass `BlissSemanticComposer(lookup)` to
 *                      enable semantic composition.
 */
class BlissTranslator(
    private val lookup:     BlissLookup,
    private val morfologik: MorfologikLemmatizer?    = null,
    private val composer:   BlissSemanticComposer?   = null
) {

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Synchronous translation.  Rule-based only (no Morfologik, no composer).
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
     * Suspend translation.  Uses **Morfologik as the primary morphological tier**
     * (tier 3b): inflected form → FSA lemma+tag → per-token indicators → CSV BCI-AV lookup.
     *
     * Must be called from a coroutine (typically [BlissViewModel.translate]).
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
        val sentenceIndicators = detectIndicators(tokens)
        return attachIndicators(symbols, sentenceIndicators)
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

    // ── step 2+3 : greedy n-gram + per-token (suspend, Morfologik-first) ─────

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
                result.addAll(resolveTokenSuspend(tokens[i], lang))
                i++
            }
        }
        return result
    }

    // ── step 3 : single-token resolution (sync) ───────────────────────────────

    private fun resolveToken(word: String): BlissSymbol {
        // Tier 0 — function-word fast-path (Patch 18)
        FUNCTION_WORDS[word]?.let {
            return lookup.toSymbol(it, word, word, MatchType.EXACT)
        }
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

    // ── step 3 : single-token resolution (suspend, Morfologik-first) ─────────

    private suspend fun resolveTokenSuspend(word: String, lang: String): List<BlissSymbol> {
        // Tier 0 — function-word fast-path: conjunctions/prepositions/negators (Patch 18)
        // Bypasses CSV and Morfologik entirely for the ~30 most common function words.
        FUNCTION_WORDS[word]?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.EXACT))
        }

        // Tier 3a — exact surface (lexicon JSON)
        lookup.lookupSurface(word)?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.EXACT))
        }

        // Tier 3b — MORFOLOGIK FSA
        morfologik?.analyzeWithTags(word, lang)?.forEach { analysis ->
            val lemma           = analysis.lemma
            val tokenIndicators = analysis.blissIndicators

            lookup.lookupSurface(lemma)?.let {
                val sym = lookup.toSymbol(it, word, lemma, MatchType.LEMMA)
                return listOf(
                    if (tokenIndicators.isEmpty()) sym
                    else sym.withIndicators(tokenIndicators.toList())
                )
            }
            lookup.lookupLemma(lemma)?.let {
                val sym = lookup.toSymbol(it, word, lemma, MatchType.LEMMA)
                return listOf(
                    if (tokenIndicators.isEmpty()) sym
                    else sym.withIndicators(tokenIndicators.toList())
                )
            }
        }

        // Tier 3c — plain lemma lookup
        lookup.lookupLemma(word)?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.LEMMA))
        }

        // Tier 3d — POS-aware heuristic
        val gPos = heuristicPos(word)
        if (gPos != null) lookup.lookupLemmaPos(word, gPos)?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.LEMMA))
        }

        // Tier 3e — rule-based de-affixation
        for (candidate in simpleDeaffix(word)) {
            lookup.lookupSurface(candidate)?.let {
                return listOf(lookup.toSymbol(it, word, candidate, MatchType.LEMMA))
            }
            lookup.lookupLemma(candidate)?.let {
                return listOf(lookup.toSymbol(it, word, candidate, MatchType.LEMMA))
            }
            if (gPos != null) lookup.lookupLemmaPos(candidate, gPos)?.let {
                return listOf(lookup.toSymbol(it, word, candidate, MatchType.LEMMA))
            }
        }

        // Tier 3f — Room FTS4 exact
        lookup.lookupSurfaceDb(word)?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.EXACT))
        }

        // Tier 3g — Semantic composition (Patch 7)
        composer?.composeStructured(word, lang)?.let { composed ->
            val componentSymbols = composed.components.map { component ->
                BlissSymbol(
                    bciAvId    = component.symbol.bciAvId,
                    name       = component.lemma,
                    sourceWord = word,
                    lemma      = component.lemma,
                    matchType  = MatchType.SEMANTIC
                ).let { sym ->
                    val indNames = component.renderAttachments
                        .filter { it.isOverlay }
                        .mapNotNull { indicatorIdToName(it.bciIndicatorId) }
                    if (indNames.isEmpty()) sym else sym.withIndicators(indNames)
                }
            }
            if (componentSymbols.isNotEmpty()) return componentSymbols
        }

        // Tier 3h — UNKNOWN
        return listOf(unknownSymbol(word))
    }

    /**
     * Maps a BCI combining-indicator id to the Bliss indicator name used by
     * [attachIndicators].  Returns null for unknown ids.
     */
    private fun indicatorIdToName(bciIndicatorId: Int): String? = when (bciIndicatorId) {
        BCI_INDICATOR_PLURAL -> INDICATOR_PLURAL
        BCI_INDICATOR_PAST   -> INDICATOR_PAST
        BCI_INDICATOR_FUTURE -> INDICATOR_FUTURE
        else                 -> null
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

    /**
     * Attaches [indicators] to every non-UNKNOWN symbol that does **not** already
     * carry per-token indicators.
     */
    internal fun attachIndicators(
        symbols: List<BlissSymbol>,
        indicators: Set<String>
    ): List<BlissSymbol> {
        if (indicators.isEmpty()) return symbols
        return symbols.map { sym ->
            when {
                sym.matchType == MatchType.UNKNOWN -> sym
                sym.indicators.isNotEmpty()        -> sym
                else                               -> sym.withIndicators(indicators.toList())
            }
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

        // BCI combining-indicator ids
        private const val BCI_INDICATOR_PLURAL = 9011
        private const val BCI_INDICATOR_PAST   = 9007
        private const val BCI_INDICATOR_FUTURE = 9008

        private val PUNCT_RE = Pattern.compile("[^\\p{L}\\p{Nd}\\s'-]").toRegex()
        private val SPACE_RE = Pattern.compile("\\s+").toRegex()

        /**
         * Patch 18 — Tier-0 function-word map.
         *
         * Maps each function-word surface form (lowercased) to its canonical BCI-AV ID.
         * Covers IT / EN / DE / FR / ES / NL / PT / PL aliases.
         *
         * BCI-AV IDs (official BCI-AV symbol list):
         *   12335 = and/e/und/et/y/en/e/i
         *   12343 = or/o/oder/ou/o/of/ou/lub
         *   12346 = but/ma/aber/mais/pero/maar/mas/ale
         *   12344 = if/se/wenn/si/si/als/se/jesli
         *   12348 = because/perche/weil/parce/porque/omdat/porque/bo
         *   14951 = with/con/mit/avec/con/met/com/z
         *   14960 = for/per/fuer/pour/para/voor/para/dla
         *   25564 = to/a/zu/a/a/naar/a/do
         *   25563 = of/di/von/de/de/van/de/od
         *   25565 = in/in/in/en/en/in/em/w
         *   17720 = not/non/nicht/ne/no/niet/nao/nie
         *   17744 = no/no/nein/non/no/nee/nao/nie
         *   12347 = when/quando/wenn/quand/cuando/wanneer/quando/kiedy
         *   12349 = so/quindi/also/donc/entonces/dus/entao/wiec
         *   14941 = from/da/von/de/de/van/de/od
         *   14945 = by/da/von/par/por/door/por/przez
         *   14942 = at/a/an/a/a/bij/em/przy
         *   14943 = on/su/auf/sur/en/op/em/na
         */
        val FUNCTION_WORDS: Map<String, Int> = mapOf(
            // ── and ──
            "and"     to 12335,
            "e"       to 12335,  // IT
            "und"     to 12335,  // DE
            "et"      to 12335,  // FR
            "y"       to 12335,  // ES
            "en"      to 12335,  // NL
            "i"       to 12335,  // PL
            // ── or ──
            "or"      to 12343,
            "o"       to 12343,  // IT/ES
            "oder"    to 12343,  // DE
            "ou"      to 12343,  // FR/PT
            "of"      to 12343,  // NL
            "lub"     to 12343,  // PL
            // ── but ──
            "but"     to 12346,
            "ma"      to 12346,  // IT
            "aber"    to 12346,  // DE
            "mais"    to 12346,  // FR/PT
            "pero"    to 12346,  // ES
            "maar"    to 12346,  // NL
            "ale"     to 12346,  // PL
            // ── if ──
            "if"      to 12344,
            "se"      to 12344,  // IT/ES/PT
            "wenn"    to 12344,  // DE
            "si"      to 12344,  // FR/ES
            "als"     to 12344,  // NL
            // ── with ──
            "with"    to 14951,
            "con"     to 14951,  // IT/ES
            "mit"     to 14951,  // DE
            "avec"    to 14951,  // FR
            "met"     to 14951,  // NL
            "com"     to 14951,  // PT
            "z"       to 14951,  // PL
            // ── for ──
            "for"     to 14960,
            "per"     to 14960,  // IT
            "pour"    to 14960,  // FR
            "para"    to 14960,  // ES/PT
            "voor"    to 14960,  // NL
            "dla"     to 14960,  // PL
            // ── not ──
            "not"     to 17720,
            "non"     to 17720,  // IT/FR
            "nicht"   to 17720,  // DE
            "nie"     to 17720,  // PL
            "nao"     to 17720,  // PT
            "niet"    to 17720,  // NL
            // ── no ──
            "no"      to 17744,  // EN/ES
            "nein"    to 17744,  // DE
            // ── in ──
            "in"      to 25565,
            "w"       to 25565,  // PL
            // ── of ──
            "di"      to 25563,  // IT
            "von"     to 25563,  // DE
            "de"      to 25563,  // FR/ES/PT/NL
            "van"     to 25563,  // NL (also DE)
            "od"      to 25563,  // PL
            // ── to ──
            "to"      to 25564,
            "zu"      to 25564,  // DE
            "naar"    to 25564,  // NL
            "do"      to 25564,  // PL
            // ── at/on/from/by — short prepositions ──
            "at"      to 14942,
            "on"      to 14943,
            "su"      to 14943,  // IT
            "op"      to 14943,  // NL
            "na"      to 14943,  // PL
            "from"    to 14941,
            "da"      to 14941,  // IT/PT
            "by"      to 14945,
            "par"     to 14945,  // FR
            "por"     to 14945,  // ES/PT
            "door"    to 14945,  // NL
            "przez"   to 14945,  // PL
            // ── when / so ──
            "when"    to 12347,
            "quando"  to 12347,  // IT/PT
            "quand"   to 12347,  // FR
            "cuando"  to 12347,  // ES
            "wanneer" to 12347,  // NL
            "kiedy"   to 12347,  // PL
            "so"      to 12349,
            "quindi"  to 12349,  // IT
            "also"    to 12349,  // DE
            "donc"    to 12349,  // FR
            "entonces" to 12349, // ES
            "dus"     to 12349,  // NL
            "entao"   to 12349,  // PT
            "wiec"    to 12349   // PL
        )

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
