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
 *       3-0. Function-word fast-path (tier 0)           → FUNCTION_WORD  ← PATCH 19
 *       3a.  Exact surface lookup                       → EXACT
 *       3b.  Morfologik FSA → lemma+tag → indicators    → LEMMA  ← PRIMARY
 *       3c.  Plain lemma lookup                         → LEMMA
 *       3d.  POS-aware heuristic + CSV                  → LEMMA
 *       3e.  Rule-based de-affixation                   → LEMMA
 *       3f.  Room FTS4 exact                            → EXACT
 *       3g.  Semantic composition                       → SEMANTIC / per-component ← PATCH 7
 *       3h.  UNKNOWN (distinct per token)
 *
 * ## Patch 18 — Tier-0 function-word fast-path
 *
 *  Short function words (conjunctions, prepositions, negators) resolved
 *  against [FUNCTION_WORDS] before any CSV / Morfologik lookup.
 *
 * ## Patch 19 — Structured function-word resolver
 *
 *  Replaces the flat-map lookup with [resolveFunctionWord] which:
 *  - Normalises apostrophes (Unicode curly → ASCII) before lookup
 *  - Expands Italian contractions: all'→al, dell'→del, nell'→nel, sull'→sul etc.
 *  - Covers articles, particles (ci, si, ne, mi, ti, vi)
 *  - Produces [MatchType.FUNCTION_WORD] (distinct from EXACT)
 *  - Carries [BlissSymbol.resolutionSource] for diagnostics
 *  - Unknown tokens get a distinct resolutionSource ("unknown:<word>")
 *    to prevent visual collapse of different UNKNOWN chips on ID 17729.
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
            // Patch 19: normalise Unicode curly apostrophes to ASCII before PUNCT_RE
            // so contractions like all\u2019, dell\u2019, nell\u2019 are preserved
            // as all', dell', nell' and correctly handled by normalizeFunctionWordForm().
            .replace('\u2019', '\'')   // RIGHT SINGLE QUOTATION MARK → ASCII apostrophe
            .replace('\u2018', '\'')   // LEFT SINGLE QUOTATION MARK  → ASCII apostrophe
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

    // ── Patch 19: function-word resolver ──────────────────────────────────────

    /**
     * Data class carrying the result of a function-word rule lookup.
     * Used internally by [resolveFunctionWord] to build a [BlissSymbol]
     * with full diagnostic metadata.
     */
    private data class FunctionWordRule(
        val bciAvId:          Int,
        val resolutionSource: String,
        val canonicalForm:    String
    )

    /**
     * Normalises a token for function-word lookup.
     *
     * Handles:
     * - Lowercasing (already done by normalise(), but safe to repeat)
     * - Italian elisions with ASCII apostrophe: all'→al, dell'→del,
     *   nell'→nel, sull'→sul, coll'→col, un'→un, l'→il
     *
     * Note: Unicode curly apostrophes are normalised to ASCII in [normalise()]
     * before tokenisation, so this function only needs to handle ASCII '.
     */
    internal fun normalizeFunctionWordForm(token: String): String {
        val t = token.lowercase(Locale.ROOT).trim()
        return when {
            // Italian elisions — most common contractions
            t == "all'"   -> "al"
            t == "all'"   -> "al"
            t == "dell'"  -> "del"
            t == "nell'"  -> "nel"
            t == "sull'"  -> "sul"
            t == "coll'"  -> "col"
            t == "dall'"  -> "da"
            t == "un'"    -> "un"
            t == "l'"     -> "il"
            t == "l'"     -> "il"
            t == "un'"    -> "un"
            else -> t
        }
    }

    /**
     * Tier-0 function-word resolver (Patch 19).
     *
     * Resolves [word] against the structured function-word rules covering:
     * - Italian prepositions (semplici + contratte)
     * - Italian conjunctions and particles
     * - Italian articles
     * - Multi-language entries via [FUNCTION_WORDS] fallback
     *
     * Returns null if the word is not a known function word.
     * Returned [BlissSymbol] carries:
     * - [MatchType.FUNCTION_WORD] (not EXACT)
     * - [BlissSymbol.resolutionSource] with a diagnostic tag
     */
    private fun resolveFunctionWord(word: String): BlissSymbol? {
        val normalized = normalizeFunctionWordForm(word)

        val rule: FunctionWordRule? = when (normalized) {
            // ── Italian prepositions semplici ──────────────────────────────
            "a"   -> FunctionWordRule(25564, "function-word:it:a-to",   "a")
            "di"  -> FunctionWordRule(25563, "function-word:it:direct",  "di")
            "da"  -> FunctionWordRule(14941, "function-word:it:direct",  "da")
            "in"  -> FunctionWordRule(25565, "function-word:it:direct",  "in")
            "su"  -> FunctionWordRule(14943, "function-word:it:direct",  "su")
            "con" -> FunctionWordRule(14951, "function-word:it:direct",  "con")
            "per" -> FunctionWordRule(14960, "function-word:it:direct",  "per")
            "tra" -> FunctionWordRule(14942, "function-word:it:approx",  "tra")
            "fra" -> FunctionWordRule(14942, "function-word:it:approx",  "fra")
            // ── Contrazioni articolate IT: a+art ──────────────────────────
            "al"    -> FunctionWordRule(25564, "function-word:it:contracted-a-il",   "al")
            "allo"  -> FunctionWordRule(25564, "function-word:it:contracted-a-lo",   "allo")
            "alla"  -> FunctionWordRule(25564, "function-word:it:contracted-a-la",   "alla")
            "ai"    -> FunctionWordRule(25564, "function-word:it:contracted-a-i",    "ai")
            "agli"  -> FunctionWordRule(25564, "function-word:it:contracted-a-gli",  "agli")
            "alle"  -> FunctionWordRule(25564, "function-word:it:contracted-a-le",   "alle")
            // ── Contrazioni articolate IT: di+art ─────────────────────────
            "del"   -> FunctionWordRule(25563, "function-word:it:contracted-di-il",  "del")
            "dello" -> FunctionWordRule(25563, "function-word:it:contracted-di-lo",  "dello")
            "della" -> FunctionWordRule(25563, "function-word:it:contracted-di-la",  "della")
            "dei"   -> FunctionWordRule(25563, "function-word:it:contracted-di-i",   "dei")
            "degli" -> FunctionWordRule(25563, "function-word:it:contracted-di-gli", "degli")
            "delle" -> FunctionWordRule(25563, "function-word:it:contracted-di-le",  "delle")
            // ── Contrazioni articolate IT: in+art ─────────────────────────
            "nel"   -> FunctionWordRule(25565, "function-word:it:contracted-in-il",  "nel")
            "nello" -> FunctionWordRule(25565, "function-word:it:contracted-in-lo",  "nello")
            "nella" -> FunctionWordRule(25565, "function-word:it:contracted-in-la",  "nella")
            "nei"   -> FunctionWordRule(25565, "function-word:it:contracted-in-i",   "nei")
            "negli" -> FunctionWordRule(25565, "function-word:it:contracted-in-gli", "negli")
            "nelle" -> FunctionWordRule(25565, "function-word:it:contracted-in-le",  "nelle")
            // ── Contrazioni articolate IT: su+art ─────────────────────────
            "sul"   -> FunctionWordRule(14943, "function-word:it:contracted-su-il",  "sul")
            "sullo" -> FunctionWordRule(14943, "function-word:it:contracted-su-lo",  "sullo")
            "sulla" -> FunctionWordRule(14943, "function-word:it:contracted-su-la",  "sulla")
            "sui"   -> FunctionWordRule(14943, "function-word:it:contracted-su-i",   "sui")
            "sugli" -> FunctionWordRule(14943, "function-word:it:contracted-su-gli", "sugli")
            "sulle" -> FunctionWordRule(14943, "function-word:it:contracted-su-le",  "sulle")
            // ── Contrazioni articolate IT: con+art ────────────────────────
            "col"   -> FunctionWordRule(14951, "function-word:it:contracted-con-il", "col")
            "coi"   -> FunctionWordRule(14951, "function-word:it:contracted-con-i",  "coi")
            // ── Contrazioni IT: da+art ────────────────────────────────────
            "dal"   -> FunctionWordRule(14941, "function-word:it:contracted-da-il",  "dal")
            "dallo" -> FunctionWordRule(14941, "function-word:it:contracted-da-lo",  "dallo")
            "dalla" -> FunctionWordRule(14941, "function-word:it:contracted-da-la",  "dalla")
            "dai"   -> FunctionWordRule(14941, "function-word:it:contracted-da-i",   "dai")
            "dagli" -> FunctionWordRule(14941, "function-word:it:contracted-da-gli", "dagli")
            "dalle" -> FunctionWordRule(14941, "function-word:it:contracted-da-le",  "dalle")
            // ── Articoli IT ───────────────────────────────────────────────
            "il"    -> FunctionWordRule(14942, "function-word:it:article",  "il")
            "lo"    -> FunctionWordRule(14942, "function-word:it:article",  "lo")
            "la"    -> FunctionWordRule(14942, "function-word:it:article",  "la")
            "i"     -> FunctionWordRule(14942, "function-word:it:article",  "i")
            "gli"   -> FunctionWordRule(14942, "function-word:it:article",  "gli")
            "le"    -> FunctionWordRule(14942, "function-word:it:article",  "le")
            "un"    -> FunctionWordRule(14942, "function-word:it:article",  "un")
            "uno"   -> FunctionWordRule(14942, "function-word:it:article",  "uno")
            "una"   -> FunctionWordRule(14942, "function-word:it:article",  "una")
            // ── Congiunzioni IT ───────────────────────────────────────────
            "e"     -> FunctionWordRule(12335, "function-word:it:direct",   "e")
            "ed"    -> FunctionWordRule(12335, "function-word:it:direct",   "ed")
            "o"     -> FunctionWordRule(12343, "function-word:it:direct",   "o")
            "od"    -> FunctionWordRule(12343, "function-word:it:direct",   "od")
            "ma"    -> FunctionWordRule(12346, "function-word:it:direct",   "ma")
            "però"  -> FunctionWordRule(12346, "function-word:it:direct",   "però")
            "se"    -> FunctionWordRule(12344, "function-word:it:direct",   "se")
            "che"   -> FunctionWordRule(12347, "function-word:it:approx-that", "che")
            "perché" -> FunctionWordRule(12348, "function-word:it:direct",  "perché")
            "perche" -> FunctionWordRule(12348, "function-word:it:direct",  "perche")
            "quindi" -> FunctionWordRule(12349, "function-word:it:direct",  "quindi")
            "però"   -> FunctionWordRule(12346, "function-word:it:direct",  "però")
            "quando" -> FunctionWordRule(12347, "function-word:it:direct",  "quando")
            // ── Negazione IT ──────────────────────────────────────────────
            "non"   -> FunctionWordRule(17720, "function-word:it:direct",   "non")
            "no"    -> FunctionWordRule(17744, "function-word:it:direct",   "no")
            // ── Particelle pronominali IT ─────────────────────────────────
            // Mapped to BCI-AV approximations for short grammatical particles.
            // ci=here/there≈25565(in), si=self≈reflexive marker, ne=of-it≈25563(of)
            "ci"    -> FunctionWordRule(25565, "function-word:it:particle-ci",  "ci")
            "ne"    -> FunctionWordRule(25563, "function-word:it:particle-ne",  "ne")
            "mi"    -> FunctionWordRule(12335, "function-word:it:particle-mi",  "mi")
            "ti"    -> FunctionWordRule(12335, "function-word:it:particle-ti",  "ti")
            "vi"    -> FunctionWordRule(25565, "function-word:it:particle-vi",  "vi")
            "si"    -> FunctionWordRule(12344, "function-word:it:particle-si",  "si")
            // ── Fallback: look up in the flat FUNCTION_WORDS map (EN/DE/FR/ES/NL/PL) ──
            else -> FUNCTION_WORDS[normalized]?.let { id ->
                FunctionWordRule(id, "function-word:generic", normalized)
            }
        }

        rule ?: return null

        // Look up canonical name from the Bliss lexicon for this BCI-AV ID.
        // Falls back to the normalized form itself if the ID is not in the loaded lexicon.
        val symbolName = lookup.getNameForId(rule.bciAvId) ?: rule.canonicalForm

        return BlissSymbol(
            bciAvId          = rule.bciAvId,
            name             = symbolName,
            sourceWord       = word,
            lemma            = rule.canonicalForm,
            matchType        = MatchType.FUNCTION_WORD,
            resolutionSource = rule.resolutionSource
        )
    }

    // ── step 3 : single-token resolution (sync) ───────────────────────────────

    private fun resolveToken(word: String): BlissSymbol {
        // Tier 0 — structured function-word resolver (Patch 19)
        resolveFunctionWord(word)?.let { return it }

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
        // Tier 0 — structured function-word resolver (Patch 19)
        // Handles normalisation, contractions, articles, particles.
        resolveFunctionWord(word)?.let { return listOf(it) }

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

        // Tier 3h — UNKNOWN (distinct resolutionSource per token to avoid
        // visual collapse of different UNKNOWN chips on the same BCI-AV ID 17729)
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
        bciAvId          = BlissSymbol.UNKNOWN_SYMBOL_ID,
        name             = "unknown",
        sourceWord       = word,
        lemma            = word,
        matchType        = MatchType.UNKNOWN,
        resolutionSource = "unknown:$word"
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
         * Patch 18/19 — Tier-0 function-word map (flat fallback).
         *
         * This map is the fallback for [resolveFunctionWord] when the structured
         * Italian-first rules do not match. It covers EN/DE/FR/ES/NL/PL.
         * Italian entries are handled by the structured resolver above.
         *
         * BCI-AV IDs (official BCI-AV symbol list):
         *   12335 = and       12343 = or        12346 = but       12344 = if
         *   12348 = because   14951 = with      14960 = for       25564 = to
         *   25563 = of        25565 = in        17720 = not       17744 = no
         *   12347 = when      12349 = so/then   14941 = from      14945 = by
         *   14942 = at        14943 = on
         */
        val FUNCTION_WORDS: Map<String, Int> = mapOf(
            // ── and ──
            "and"     to 12335,
            "e"       to 12335,  // IT (also in structured resolver)
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
            "fuer"    to 14960,  // DE romanised
            "f\u00fcr" to 14960, // DE with umlaut
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
            "di"      to 25563,  // IT (also in structured resolver)
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
            "su"      to 14943,  // IT (also in structured resolver)
            "op"      to 14943,  // NL
            "na"      to 14943,  // PL
            "from"    to 14941,
            "da"      to 14941,  // IT/PT (also in structured resolver)
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
            "wiec"    to 12349,  // PL
            // ── because ──
            "because" to 12348,
            "perche"  to 12348,  // IT (no accent)
            "perch\u00e9" to 12348, // IT with accent
            "weil"    to 12348,  // DE
            "parce"   to 12348,  // FR (parce que)
            "porque"  to 12348,  // ES/PT
            "omdat"   to 12348,  // NL
            "bo"      to 12348,  // PL
            // ── that/che ──
            "that"    to 12347,
            "che"     to 12347,  // IT (also in structured resolver)
            "que"     to 12347,  // FR/ES/PT
            "dass"    to 12347,  // DE
            "dat"     to 12347,  // NL
            "ze"      to 12347   // PL
        )

        private val PAST_IT_AUX_RE =
            Regex("\\b(ha|hanno|aveva|avevano|ebbe|ebbero|\u00e8 stato|sono stati|ho|abbiamo)\\b")
        private val PAST_IT_PARTICIPLE_RE = Regex("[a-z]{3,}(ato|ito|uto)")
        private val PAST_EN_RE =
            Regex("\\b(had|has|have|was|were|did)\\b.*\\b\\w+ed\\b")
        private val PAST_FR_RE =
            Regex("\\b(avait|avaient|avais|a|ont|est|sont)\\b.*\\b\\w+(\u00e9|i|u)\\b")
        private val PAST_DE_RE =
            Regex("\\b(hatte|hatten|hat|ist|sind|wurde|wurden)\\b.*\\bge\\w+\\b")
        private val PAST_ES_RE =
            Regex("\\b(tuvo|tuvieron|hab\u00eda|hab\u00edan|ha|han|fue|fueron)\\b.*\\b\\w+(ado|ido)\\b")

        private val FUTURE_EN_RE =
            Regex("\\b(will|shall|going to|won't|shan't)\\b")
        private val FUTURE_IT_RE =
            Regex("\\b(andr\u00e0|andranno|verr\u00e0|verranno|sar\u00e0|saranno|far\u00e0|faranno|" +
                       "dovr\u00e0|dovranno|potr\u00e0|potranno|vorr\u00e0|vorranno|" +
                       "\\w+(er\u00e0|ir\u00e0|ar\u00e0|eranno|iranno|aranno))\\b")
        private val FUTURE_ES_RE =
            Regex("\\b(ir\u00e1|ir\u00e1n|ser\u00e1|ser\u00e1n|har\u00e1|har\u00e1n|tendr\u00e1|tendr\u00e1n|" +
                       "\\w+(ar\u00e1|er\u00e1|ir\u00e1|ar\u00e1n|er\u00e1n|ir\u00e1n))\\b")
        private val FUTURE_DE_RE =
            Regex("\\b(wird|werden|werde|wirst|werdet)\\b")
        private val FUTURE_FR_RE =
            Regex("\\b(ira|iront|sera|seront|aura|auront|fera|feront|" +
                       "\\w+(era|ira|eras|iras|erons|irons|erez|irez|eront|iront))\\b")
    }
}
