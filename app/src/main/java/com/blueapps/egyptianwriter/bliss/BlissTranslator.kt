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
 *       3a. Exact surface lookup                        → EXACT
 *       3b. Morfologik FSA → lemma+tag → per-token indicators → LEMMA  ← PRIMARY
 *       3c. Plain lemma lookup (word already base form) → LEMMA
 *       3d. POS-aware heuristic + CSV                   → LEMMA
 *       3e. Rule-based de-affixation                    → LEMMA
 *       3f. Room FTS4 exact                             → EXACT
 *       3g. Semantic composition ([BlissSemanticComposer])  → SEMANTIC / per-component ← PATCH 7
 *       3h. UNKNOWN
 *
 *  Tier 3b now uses [MorfologikLemmatizer.analyzeWithTags] to obtain both the
 *  canonical lemma and the raw FSA POS tag for each analysis candidate.
 *  Per-token indicators (PLURAL / PAST / FUTURE) are derived from that tag via
 *  [MorfologikTagMapper.toBlissIndicators] and attached directly to the resolved
 *  symbol, replacing the sentence-level [detectIndicators] heuristic for tokens
 *  that Morfologik can analyse.  [detectIndicators] is preserved as a fallback
 *  for multi-token patterns (auxiliaries, periphrastic tenses) not expressible
 *  in a single FSA tag.
 *
 *  Tier 3g (Patch 7) now calls [BlissSemanticComposer.composeStructured] instead
 *  of the legacy [BlissSemanticComposer.compose] shim.  Each [ResolvedBlissComponent]
 *  in the resulting [ComposedBlissWord] is converted to a separate [BlissSymbol]
 *  with [MatchType.SEMANTIC], so a single input token can expand to N output symbols
 *  (one per semantic component).  The old COMPOUND single-symbol path is removed.
 *  If [composer] is null (default), this tier is silently skipped.
 *
 * ## Patch 4 — 1-token → N-symbols
 *
 *  [resolveTokenSuspend] returns `List<BlissSymbol>`.  Patch 7 takes full advantage
 *  of this: tier 3g may now return more than one symbol per input token.
 *
 * Morfologik covers all 8 languages (it, en, de, fr, es, nl, pl, pt).
 * When the .dict asset is absent for a language, that tier degrades
 * gracefully and the pipeline continues with tiers 3c–3h.
 *
 * The translator is stateless and thread-safe after construction.
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
     * Per-token indicators derived from the FSA tag take precedence over the
     * sentence-level [detectIndicators] heuristic.  Sentence-level detection is
     * still applied as a fallback for multi-token patterns (e.g. "going to",
     * compound auxiliaries) that a single-token tag cannot capture; however,
     * tokens that already carry per-token indicators are skipped in
     * [attachIndicators] to avoid double-tagging.
     *
     * Must be called from a coroutine (typically [BlissViewModel.translate]).
     * The Morfologik FSA lookup runs on [Dispatchers.IO] inside
     * [MorfologikLemmatizer.analyzeWithTags].
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
        // Sentence-level indicators: fallback for multi-token patterns.
        // attachIndicators skips symbols that already have per-token indicators.
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
    //
    // Pipeline:
    //   3a. Exact surface match in lexicon JSON         → EXACT
    //   3b. Morfologik FSA → lemma+tag → per-token indicators → BCI-AV  ← PRIMARY
    //   3c. Plain lemma lookup (word already base form)  → LEMMA
    //   3d. POS-aware heuristic guess + CSV             → LEMMA
    //   3e. Rule-based de-affixation + CSV              → LEMMA
    //   3f. Room FTS4 exact                             → EXACT
    //   3g. Semantic composition → one BlissSymbol per ResolvedBlissComponent ← PATCH 7
    //   3h. UNKNOWN
    //
    // Patch 7: tier 3g calls composeStructured() and expands each
    // ResolvedBlissComponent into a separate BlissSymbol(MatchType.SEMANTIC).
    // A single input token may now produce N output symbols.

    private suspend fun resolveTokenSuspend(word: String, lang: String): List<BlissSymbol> {
        // Tier 3a — exact surface (lexicon JSON: idioms, proper nouns, symbols)
        lookup.lookupSurface(word)?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.EXACT))
        }

        // Tier 3b — MORFOLOGIK FSA: inflected form → canonical lemma + POS tag → BCI-AV
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

        // Tier 3c — plain lemma lookup (word is already in base form)
        lookup.lookupLemma(word)?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.LEMMA))
        }

        // Tier 3d — POS-aware heuristic guess + CSV
        val gPos = heuristicPos(word)
        if (gPos != null) lookup.lookupLemmaPos(word, gPos)?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.LEMMA))
        }

        // Tier 3e — rule-based de-affixation (language-agnostic suffix stripping)
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

        // Tier 3f — Room FTS4 exact (words added to DB after initial CSV load)
        lookup.lookupSurfaceDb(word)?.let {
            return listOf(lookup.toSymbol(it, word, word, MatchType.EXACT))
        }

        // Tier 3g — Semantic composition (Patch 7).
        // composeStructured() returns a ComposedBlissWord whose components list
        // is expanded here: one BlissSymbol(SEMANTIC) per ResolvedBlissComponent.
        // This replaces the old compose() → single COMPOUND symbol path.
        composer?.composeStructured(word, lang)?.let { composed ->
            val componentSymbols = composed.components.map { component ->
                BlissSymbol(
                    bciAvId    = component.bciSymbolId,
                    name       = component.lemma,
                    sourceWord = word,
                    lemma      = component.lemma,
                    matchType  = MatchType.SEMANTIC
                ).let { sym ->
                    // Carry over any indicators encoded on the component (e.g. tense overlays)
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
     * [attachIndicators].  Returns null for unknown ids so they are silently
     * dropped rather than crashing the pipeline.
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
     * carry per-token indicators (i.e. symbols whose [BlissSymbol.indicators] list
     * is empty).  This prevents double-tagging for tokens resolved via Morfologik
     * tier 3b in [resolveTokenSuspend], which already embed per-token indicators.
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

        // BCI combining-indicator ids used by indicatorIdToName()
        private const val BCI_INDICATOR_PLURAL = 9011
        private const val BCI_INDICATOR_PAST   = 9007
        private const val BCI_INDICATOR_FUTURE = 9008

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
