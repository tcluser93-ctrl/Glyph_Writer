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
 * ## Patch 20 — getNameForId → nameOf fix
 *
 *  [resolveFunctionWord] previously called the non-existent
 *  `lookup.getNameForId()`.  Replaced with the existing `lookup.nameOf()`
 *  which returns a non-null String (numeric fallback when ID is absent).
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
            Log.w(TAG, "[TR] translateAsync() called before lookup.isReady — returning empty")
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        val normalised = normalise(text)
        Log.d(TAG, "[TR] translateAsync input='$text' normalised='$normalised'")
        val tokens     = normalised.split(" ").filter { it.isNotBlank() }
        val lang       = lookup.currentLang ?: "en"
        Log.d(TAG, "[TR] tokens=${tokens.size} lang='$lang'")
        val symbols    = resolveNgramsAndTokensSuspend(normalised, lang)
        Log.d(TAG, "[TR] resolveNgrams returned ${symbols.size} symbols")
        symbols.forEach { sym ->
            Log.d(TAG, "[TR] token='${sym.sourceWord}' lemma='${sym.lemma}' match=${sym.matchType}")
        }
        val sentenceIndicators = detectIndicators(tokens)
        val result = attachIndicators(symbols, sentenceIndicators)
        Log.d(TAG, "[TR] translateAsync done — final symbols=${result.size}")
        return result
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
            t == "dell'"  -> "del"
            t == "nell'"  -> "nel"
            t == "sull'"  -> "sul"
            t == "coll'"  -> "col"
            t == "dall'"  -> "da"
            t == "un'"    -> "un"
            t == "l'"     -> "il"
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
     *
     * ## Patch 20
     * Uses [BlissLookup.nameOf] (existing, non-nullable) instead of the
     * previously referenced `lookup.getNameForId()` which does not exist,
     * causing a compile error.  `nameOf()` returns the numeric ID string as
     * fallback when the ID is absent from the loaded lexicon, making the
     * explicit `?: canonicalForm` fallback unnecessary (retained as comment
     * for clarity but effectively dead code since nameOf() is non-null).
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
            "negli" -> FunctionWordRule(25565, "function-word:it:contracted-in-le",  "negli")
            "nelle" -> FunctionWordRule(25565, "function-word:it:contracted-in-gli", "nelle")
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
            "quando" -> FunctionWordRule(12347, "function-word:it:direct",  "quando")
            // ── Negazione IT ──────────────────────────────────────────────
            "non"   -> FunctionWordRule(17720, "function-word:it:direct",   "non")
            "no"    -> FunctionWordRule(17744, "function-word:it:direct",   "no")
            // ── Particelle pronominali IT ─────────────────────────────────
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

        // Patch 20: use lookup.nameOf() — existing, non-null returning method —
        // instead of the non-existent lookup.getNameForId() which caused a
        // compile error.  nameOf() returns the numeric ID string when the ID
        // is absent from the loaded lexicon, so no additional fallback is needed.
        val symbolName = lookup.nameOf(rule.bciAvId)

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
     *
     * Delegates to [BlissIndicator.nameOf] (single source of truth) instead
     * of a locally hardcoded map — see [BlissIndicator]'s KDoc for why that
     * used to be a real bug (wrong ids for "past"/"future").
     */
    private fun indicatorIdToName(bciIndicatorId: Int): String? =
        BlissIndicator.nameOf(bciIndicatorId)

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
            word.endsWith("ismo") || word.endsWith("ista")                           -> "N"
            else -> null
        }
    }

    // ── de-affixation ─────────────────────────────────────────────────────────

    private fun simpleDeaffix(word: String): List<String> {
        val candidates = mutableListOf<String>()
        // Italian verb suffixes
        for (sfx in listOf("ando", "endo", "ato", "uto", "ito", "are", "ere", "ire",
                            "erei", "eresti", "erebbe", "eremmo", "ereste", "erebbero",
                            "irei", "iresti", "irebbe", "iremmo", "ireste", "irebbero",
                            "erei", "erei")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 3)
                candidates += word.dropLast(sfx.length) + "are"
        }
        // English suffixes
        for ((sfx, rep) in listOf("ing" to "", "ed" to "", "er" to "", "est" to "",
                                   "s" to "", "es" to "", "ies" to "y")) {
            if (word.endsWith(sfx) && word.length > sfx.length + 3)
                candidates += word.dropLast(sfx.length) + rep
        }
        return candidates.distinct()
    }

    // ── companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG          = "BlissTranslator"
        private const val MAX_NGRAM_LEN = 4

        // Fix (enterprise-grade audit, 2026-07-20): these now delegate to the
        // single-source-of-truth registry in BlissIndicator instead of
        // duplicating the string/id mapping locally — see BlissIndicator's
        // KDoc for the real bug that duplication used to cause (wrong BCI-AV
        // ids for "past"/"future", silently rendering the wrong SVG).
        const val INDICATOR_PLURAL = BlissIndicator.PLURAL
        const val INDICATOR_PAST   = BlissIndicator.PAST
        const val INDICATOR_FUTURE = BlissIndicator.FUTURE

        private val PUNCT_RE = Regex("[^\\w'àáâãäåæçèéêëìíîïðñòóôõöùúûüýþÿ\\-]")
        private val SPACE_RE = Regex("\\s+")

        // ── Tense / plural detection regexes ──────────────────────────────
        private val PAST_IT_AUX_RE        = Regex("\\b(ho|hai|ha|abbiamo|avete|hanno|sono|sei|è|siamo|siete|sono)\\b")
        private val PAST_IT_PARTICIPLE_RE = Regex("[a-z]{3,}(ato|uto|ito|ato|uta|ita|ati|ute|ite)")
        private val PAST_EN_RE            = Regex("\\b(was|were|had|did|been|went|saw|made|came|took)\\b")
        private val PAST_FR_RE            = Regex("\\b(ai|as|a|avons|avez|ont|suis|es|est|sommes|êtes|sont)\\b")
        private val PAST_DE_RE            = Regex("\\b(hatte|hatten|war|waren|wurde|wurden|hat|haben|ist|sind)\\b")
        private val PAST_ES_RE            = Regex("\\b(tuve|tuviste|tuvo|tuvimos|tuvisteis|tuvieron|fui|fuiste|fue|fuimos|fuisteis|fueron)\\b")

        private val FUTURE_EN_RE = Regex("\\b(will|shall|going to|gonna)\\b")
        private val FUTURE_IT_RE = Regex("\\b(andrò|andrai|andrà|andremo|andrete|andranno|farò|farai|farà|faremo|farete|faranno|sarò|sarai|sarà|saremo|sarete|saranno)\\b")
        private val FUTURE_ES_RE = Regex("\\b(iré|irás|irá|iremos|iréis|irán|será|serás|seré|seremos|seréis|serán)\\b")
        private val FUTURE_DE_RE = Regex("\\b(werde|wirst|wird|werden|werdet|wird)\\b")
        private val FUTURE_FR_RE = Regex("\\b(irai|iras|ira|irons|irez|iront|serai|seras|sera|serons|serez|seront)\\b")

        /**
         * Flat function-word map for languages other than Italian.
         * Used as tier-0 fallback when [resolveFunctionWord]'s structured
         * Italian rules do not match.
         */
        @JvmField
        val FUNCTION_WORDS: Map<String, Int> = mapOf(
            // English
            "and" to 12335, "or" to 12343, "but" to 12346, "if" to 12344,
            "that" to 12347, "because" to 12348, "so" to 12349, "when" to 12347,
            "not" to 17720, "no" to 17744,
            "the" to 14942, "a" to 14942, "an" to 14942,
            "to" to 25564, "of" to 25563, "in" to 25565, "on" to 14943,
            "at" to 25564, "from" to 14941, "with" to 14951, "for" to 14960,
            "between" to 14942, "among" to 14942,
            "i" to 14942, "you" to 14942, "he" to 14942, "she" to 14942,
            "we" to 14942, "they" to 14942, "it" to 14942,
            // German
            "und" to 12335, "oder" to 12343, "aber" to 12346, "wenn" to 12344,
            "dass" to 12347, "weil" to 12348, "also" to 12349, "nicht" to 17720,
            "der" to 14942, "die" to 14942, "das" to 14942, "ein" to 14942, "eine" to 14942,
            "zu" to 25564, "von" to 25563, "in" to 25565, "auf" to 14943,
            "mit" to 14951, "für" to 14960, "zwischen" to 14942,
            // French
            "et" to 12335, "ou" to 12343, "mais" to 12346, "si" to 12344,
            "que" to 12347, "parce" to 12348, "donc" to 12349, "ne" to 17720, "pas" to 17720,
            "le" to 14942, "la" to 14942, "les" to 14942, "un" to 14942, "une" to 14942,
            "à" to 25564, "de" to 25563, "dans" to 25565, "sur" to 14943,
            "avec" to 14951, "pour" to 14960, "entre" to 14942,
            // Spanish
            "y" to 12335, "o" to 12343, "pero" to 12346, "si" to 12344,
            "que" to 12347, "porque" to 12348, "entonces" to 12349, "no" to 17720,
            "el" to 14942, "la" to 14942, "los" to 14942, "las" to 14942,
            "un" to 14942, "una" to 14942, "unos" to 14942, "unas" to 14942,
            "a" to 25564, "de" to 25563, "en" to 25565, "sobre" to 14943,
            "con" to 14951, "para" to 14960, "entre" to 14942,
            // Dutch
            "en" to 12335, "of" to 12343, "maar" to 12346, "als" to 12344,
            "dat" to 12347, "omdat" to 12348, "dus" to 12349, "niet" to 17720,
            "de" to 14942, "het" to 14942, "een" to 14942,
            "naar" to 25564, "van" to 25563, "in" to 25565, "op" to 14943,
            "met" to 14951, "voor" to 14960, "tussen" to 14942,
            // Polish
            "i" to 12335, "lub" to 12343, "ale" to 12346, "jeśli" to 12344,
            "że" to 12347, "bo" to 12348, "więc" to 12349, "nie" to 17720,
            "w" to 25565, "na" to 14943, "z" to 14951, "do" to 25564, "od" to 14941
        )
    }
}
