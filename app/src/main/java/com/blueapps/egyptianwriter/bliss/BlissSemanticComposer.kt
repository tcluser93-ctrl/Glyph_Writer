package com.blueapps.egyptianwriter.bliss

import android.util.Log
import java.util.Locale

/**
 * Tier 3g — Semantic composition helper.
 *
 * Replaces the legacy orthographic pivot-split with a multi-stage strategy
 * that respects the BCI/Bliss compositional principles.
 *
 * ## Patch 6 additions
 *
 * [composeStructured] is the new primary entry point.  It returns a
 * [ComposedBlissWord] that preserves per-component symbol, lemma, POS,
 * indicators and render-attachment metadata.  The structured output feeds
 * directly into [BlissRenderer]'s overlay pipeline.
 *
 * The legacy [compose] method is kept as a backward-compatibility shim:
 * it calls [composeStructured] and collapses the result via
 * [ComposedBlissWord.toFlatSymbol].  Callers should migrate to
 * [composeStructured] at their own pace.
 *
 * ## Stage A — WordNet direct-synonym substitution (EG audit redesign, 2026-07-22)
 * When [word] has no direct Bliss symbol (it already failed tiers 3a-3f, the
 * exact-match/lemma tiers), Stage A looks the word up in a *separate*
 * WordNet-derived index ([wordNet]) rather than re-querying the same Bliss
 * lexicon those tiers just missed on. If a direct synonym (same WordNet
 * synset, hop level 0) has a Bliss symbol, that symbol is used directly —
 * e.g. Italian "oceano" has no Bliss entry, but shares a synset with
 * "mare", which does: a pure semantic hit, no new composition. See
 * [WordNetIndex]'s KDoc and `Report_EG_Tier3g_Opzioni_A_D.md` for the full
 * rationale, data pipeline, and measured coverage.
 *
 * This replaces the original Stage A (Patch 5), which re-derived a BCI-AV
 * id via [BlissLookup.lookupSurface]/[BlissLookup.lookupLemma] on the same
 * `word` tiers 3a/3c had already tried and failed on — a precondition that
 * can never hold at the real call site, making it permanently unreachable
 * in production. [wordNet] is `null`-safe: with no [WordNetIndex] supplied
 * (or none loaded for the active language), Stage A is a silent no-op,
 * same as the rest of this tier was before this redesign.
 *
 * Hypernym hits (level >= 1 — a broader, less specific concept than
 * [word]) are deliberately *not* handled here: see Stage B below.
 *
 * ## Stage B — Hypernym classifier + literal specifier (EG audit redesign, 2026-07-22)
 * When Stage A finds no *direct* synonym, Stage B asks the same
 * [WordNetIndex] the same question Stage A did — but this time accepts a
 * hypernym-level hit (a broader category, found by climbing up to
 * [WordNetIndex.MAX_HYPERNYM_LEVELS] hops; e.g. Italian "veliero" has no
 * Bliss entry and no direct synonym either, but its hypernym "boat" does).
 * A single generic symbol for that broader category loses the specific
 * meaning of [word], so Stage B composes **two** components instead of
 * one: `[classifier, specifier]`, where the classifier is the hypernym
 * symbol found via [wordNet] (genuinely derived from the word's own
 * semantic meaning) and the specifier is [word] itself, carried through
 * verbatim as an unresolved ([BlissSymbol.MatchType.UNKNOWN]) component —
 * pairing a general pictogram with the literal typed word for specificity
 * is itself an established AAC pattern, not a placeholder.
 *
 * This replaces the original Stage B (Patch 5), which derived its
 * "classifier" by re-resolving [word] to a BCI-AV id via
 * [BlissLookup.lookupSurface]/[BlissLookup.lookupLemma] (the exact same
 * unreachable precondition Stage A had — see above) and then searching
 * [BlissLookup.synsets] for the closest WordNet-offset match within a
 * coarse POS bucket. Beyond being unreachable for the same reason as the
 * old Stage A, that design was self-contradictory even in isolation: it
 * required [word] to already have a resolved BCI-AV id with a known
 * synset in order to find a classifier for a word that, by definition,
 * doesn't have one. The new design fixes that at the root by deriving the
 * classifier from [wordNet] — the same WordNet-based mechanism Stage A
 * uses — rather than from a Bliss id that cannot exist for an unresolved
 * word.
 *
 * ## Stage C — Orthographic pivot-split (legacy fallback, off by default)
 * The original exhaustive pivot-split over grapheme substrings.  Not BCI-
 * conformant but useful for maximising coverage in practice.  Disabled by
 * default via [enableOrthographicFallback].  When enabled, results carry
 * [BlissSymbol.MatchType.COMPOUND] to distinguish them from Stage A/B results
 * that carry [BlissSymbol.MatchType.SEMANTIC].
 *
 * ## Thread-safety
 * This class is stateless after construction. Safe to share across
 * coroutines — [wordNet], if supplied, has its own thread-safety guarantee
 * (see [WordNetIndex]'s KDoc).
 *
 * @param lookup                    A ready [BlissLookup] instance.
 * @param wordNet                   Optional [WordNetIndex] powering the Stage A
 *                                  substitution above; if `null` (or not yet
 *                                  loaded for the active language), Stage A
 *                                  is a no-op.
 * @param enableOrthographicFallback  When `true`, Stage C runs after A+B fail.
 *                                    Defaults to `false` (BCI-clean mode).
 */
class BlissSemanticComposer(
    private val lookup: BlissLookup,
    private val wordNet: WordNetIndex? = null,
    val enableOrthographicFallback: Boolean = false
) {

    // ── public entry points ───────────────────────────────────────────────────

    /**
     * Primary structured entry point (Patch 6).
     *
     * Returns a [ComposedBlissWord] preserving per-component lemma, POS,
     * indicators and overlay metadata.  Prefer this over [compose] for all
     * new callers.
     *
     * @param word  Lower-cased surface token that has already failed tiers 3a–3f.
     * @param lang  ISO-639-1 language code.
     * @return      A [ComposedBlissWord], or `null` if all stages fail.
     */
    fun composeStructured(word: String, lang: String): ComposedBlissWord? {
        val w = word.lowercase(Locale.ROOT)
        if (w.isBlank()) return null

        stageAStructured(w, lang)?.let { return it }
        stageBStructured(w, lang)?.let { return it }

        if (enableOrthographicFallback && w.length >= MIN_WORD_LEN) {
            stageCStructured(w, word, lang)?.let { return it }
        }

        Log.v(TAG, "composeStructured: all stages failed for '$w'")
        return null
    }

    /**
     * Legacy flat entry point — backward-compatibility shim.
     *
     * Delegates to [composeStructured] and collapses the result via
     * [ComposedBlissWord.toFlatSymbol].  Existing callers in
     * [BlissTranslator] tier 3g continue to work without changes.
     *
     * @deprecated Migrate callers to [composeStructured] to benefit from
     *             per-component indicators and SVG overlay support.
     */
    fun compose(word: String, lang: String): BlissSymbol? =
        composeStructured(word, lang)?.toFlatSymbol()

    // ── Stage A (structured) ─────────────────────────────────────────────────

    /**
     * Looks [word] up in [wordNet] for a *direct synonym* (hop level 0
     * only — see [WordNetIndex.findSubstitute]) and, on a hit, returns a
     * [ComposedBlissWord] with a single [ResolvedBlissComponent] wrapping
     * the substitute Bliss symbol. Hypernym-level hits (level >= 1) are
     * left for [stageBStructured] to turn into a two-component
     * classifier+specifier composition instead.
     *
     * The component's lemma is the substitute symbol's canonical name
     * ([BlissLookup.nameOf]) rather than [word] itself, since
     * [WordNetIndex.findSubstitute] only returns a BCI-AV id, not which
     * specific lemma of the matched synset it came from.
     */
    private fun stageAStructured(word: String, lang: String): ComposedBlissWord? {
        val substitute = wordNet?.findSubstitute(word)?.takeIf { it.level == 0 } ?: return null

        Log.d(TAG, "stageAStructured: '$word' → BCI ${substitute.bciAvId} (synset=${substitute.synset})")
        val substituteName = lookup.nameOf(substitute.bciAvId)
        val symbol = lookup.toSymbol(
            id     = substitute.bciAvId,
            source = word,
            lemma  = substituteName,
            mt     = BlissSymbol.MatchType.SEMANTIC
        )
        val component = ResolvedBlissComponent(
            symbol     = symbol,
            lemma      = substituteName,
            indicators = symbol.indicators
        )
        return ComposedBlissWord(
            sourceWord      = word,
            lemma           = substituteName,
            sourceLang      = lang,
            components      = listOf(component),
            compositionPath = CompositionPath.SYNONYM_SYNSET
        )
    }

    // ── Stage A (legacy shim, kept for internal consistency) ─────────────────

    private fun stageA(word: String): BlissSymbol? =
        stageAStructured(word, "")?.toFlatSymbol()

    // ── Stage B (structured) ─────────────────────────────────────────────────

    /**
     * Hypernym classifier + literal specifier, via the same [wordNet] Stage A
     * uses.
     *
     * Only accepts a [WordNetIndex.Substitute] with `level >= 1` (a
     * hypernym, not a direct synonym — [stageAStructured] already claimed
     * `level == 0`). Composes `[classifier, specifier]`:
     * - **classifier**: the hypernym's Bliss symbol, matching
     *   [BlissSymbol.MatchType.SEMANTIC], lemma = its canonical name.
     * - **specifier**: [word] itself, carried through as an unresolved
     *   ([BlissSymbol.MatchType.UNKNOWN]) component rather than invented
     *   from a nonexistent Bliss id — see this class's Stage B KDoc for why
     *   pairing a general pictogram with the literal word is the correct
     *   fallback here, not a placeholder.
     */
    private fun stageBStructured(word: String, lang: String): ComposedBlissWord? {
        val substitute = wordNet?.findSubstitute(word)?.takeIf { it.level >= 1 } ?: return null

        Log.d(TAG, "stageBStructured: '$word' → classifier BCI ${substitute.bciAvId} " +
                "(synset=${substitute.synset}, hop level=${substitute.level})")

        val classifierName = lookup.nameOf(substitute.bciAvId)
        val classifierSymbol = lookup.toSymbol(
            id     = substitute.bciAvId,
            source = word,
            lemma  = classifierName,
            mt     = BlissSymbol.MatchType.SEMANTIC
        )
        val specifierSymbol = BlissSymbol(
            bciAvId    = BlissSymbol.UNKNOWN_SYMBOL_ID,
            name       = word,
            sourceWord = word,
            lemma      = word,
            matchType  = BlissSymbol.MatchType.UNKNOWN
        )

        return ComposedBlissWord(
            sourceWord      = word,
            lemma           = word,
            sourceLang      = lang,
            components      = listOf(
                ResolvedBlissComponent(symbol = classifierSymbol, lemma = classifierName),
                ResolvedBlissComponent(symbol = specifierSymbol,  lemma = word)
            ),
            compositionPath = CompositionPath.SEMANTIC_DECOMPOSITION
        )
    }

    private fun stageB(word: String): BlissSymbol? =
        stageBStructured(word, "")?.toFlatSymbol()

    // ── Stage C (structured, legacy orthographic pivot-split) ─────────────────

    /**
     * Exhaustive pivot-split over grapheme substrings.
     * Returns a [ComposedBlissWord] with two [ResolvedBlissComponent]s (left/right).
     * Retained as an opt-in fallback ([enableOrthographicFallback] == `true`).
     */
    private fun stageCStructured(w: String, originalWord: String, lang: String): ComposedBlissWord? {
        for (pivot in MIN_FRAGMENT_LEN..(w.length - MIN_FRAGMENT_LEN)) {
            val left  = w.substring(0, pivot)
            val right = w.substring(pivot)

            val leftId  = resolveFragment(left)  ?: continue
            val rightId = resolveFragment(right) ?: continue

            Log.d(TAG, "stageCStructured: '$w' → '$left'($leftId) + '$right'($rightId)")

            val leftSymbol  = lookup.toSymbol(id = leftId,  source = left,  lemma = left,  mt = BlissSymbol.MatchType.COMPOUND)
            val rightSymbol = lookup.toSymbol(id = rightId, source = right, lemma = right, mt = BlissSymbol.MatchType.COMPOUND)

            return ComposedBlissWord(
                sourceWord      = originalWord,
                lemma           = originalWord,
                sourceLang      = lang,
                components      = listOf(
                    ResolvedBlissComponent(symbol = leftSymbol,  lemma = left),
                    ResolvedBlissComponent(symbol = rightSymbol, lemma = right)
                ),
                compositionPath = CompositionPath.ORTHOGRAPHIC
            )
        }
        Log.v(TAG, "stageCStructured: no valid split for '$w'")
        return null
    }

    private fun stageC(w: String, originalWord: String): BlissSymbol? =
        stageCStructured(w, originalWord, "")?.toFlatSymbol()

    // ── private helpers ───────────────────────────────────────────────────────

    private fun resolveFragment(fragment: String): Int? {
        if (fragment.length < MIN_FRAGMENT_LEN) return null
        return lookup.lookupSurface(fragment) ?: lookup.lookupLemma(fragment)
    }

    companion object {
        private const val TAG              = "BlissSemanticComposer"
        private const val MIN_WORD_LEN     = 6
        private const val MIN_FRAGMENT_LEN = 3
    }
}
