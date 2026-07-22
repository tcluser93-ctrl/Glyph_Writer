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
 * ## Stage A — WordNet synonym/hypernym substitution (EG audit redesign, 2026-07-22)
 * When [word] has no direct Bliss symbol (it already failed tiers 3a-3f, the
 * exact-match/lemma tiers), Stage A looks the word up in a *separate*
 * WordNet-derived index ([wordNet]) rather than re-querying the same Bliss
 * lexicon those tiers just missed on. If a direct synonym (same WordNet
 * synset) has a Bliss symbol, that symbol is used directly — e.g. Italian
 * "oceano" has no Bliss entry, but shares a synset with "mare", which does.
 * Failing that, up to [WordNetIndex.MAX_HYPERNYM_LEVELS] hypernym hops are
 * climbed (broader and broader concepts) looking for the first one that
 * does have a Bliss symbol. See [WordNetIndex]'s KDoc and
 * `Report_EG_Tier3g_Opzioni_A_D.md` for the full rationale, data pipeline,
 * and measured coverage (60-73% of otherwise-unresolved words for Italian).
 *
 * This replaces the original Stage A (Patch 5), which re-derived a BCI-AV
 * id via [BlissLookup.lookupSurface]/[BlissLookup.lookupLemma] on the same
 * `word` tiers 3a/3c had already tried and failed on — a precondition that
 * can never hold at the real call site, making it permanently unreachable
 * in production. [wordNet] is `null`-safe: with no [WordNetIndex] supplied
 * (or none loaded for the active language), Stage A is a silent no-op,
 * same as the rest of this tier was before this redesign.
 *
 * ## Stage B — Hypernym classifier (semantic bucket)
 * When Stage A misses, the synset offset of the resolved token is mapped to a
 * WordNet semantic bucket (noun coarse-range / verb coarse-range) and compared
 * against the synsets of all BCI IDs in that bucket.  The closest BCI symbol
 * (lowest |synset delta| within the bucket) is used as a **classifier** symbol
 * to build a two-element SEMANTIC composition: `[classifier, specifier]`, where
 * the specifier is the directly-resolved BCI ID if available, or null.
 *
 * Not yet redesigned (tracked as Fase 2 in the EG audit report). As a side
 * effect of the Stage A redesign above, Stage B is now *reachable* again
 * when Stage A misses — the original Stage A used to self-match and shadow
 * it unconditionally (see the removed `synsetToBciIds` self-match logic in
 * git history). In production it is still effectively a no-op, though: it's
 * gated on [BlissLookup.synsets], which is empty (`bci_blissnet.json` is an
 * unpopulated stub — see the report, §2), so `synsetOf(specifierId) < 0L`
 * makes it return `null` immediately regardless. Fase 2 needs to both
 * restore that data *and* address the deeper design issue the report flags:
 * the "classifier" should be derived from the missing word's own semantic
 * meaning (via [wordNet]), not from a Bliss id that, by construction,
 * doesn't exist for an unresolved word.
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
     * Looks [word] up in [wordNet] (direct synonym, then up to
     * [WordNetIndex.MAX_HYPERNYM_LEVELS] hypernym hops) and, on a hit,
     * returns a [ComposedBlissWord] with a single [ResolvedBlissComponent]
     * wrapping the substitute Bliss symbol.
     *
     * The component's lemma is the substitute symbol's canonical name
     * ([BlissLookup.nameOf]) rather than [word] itself — consistent with
     * how Stage B's classifier component derives its lemma the same way —
     * since [WordNetIndex.findSubstitute] only returns a BCI-AV id, not
     * which specific lemma of the matched synset it came from.
     */
    private fun stageAStructured(word: String, lang: String): ComposedBlissWord? {
        val substitute = wordNet?.findSubstitute(word) ?: return null

        Log.d(TAG, "stageAStructured: '$word' → BCI ${substitute.bciAvId} " +
                "(synset=${substitute.synset}, hop level=${substitute.level})")
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
     * Hypernym classifier via synset bucket proximity.
     *
     * Returns a [ComposedBlissWord] with either one component (classifier ==
     * specifier) or two components (classifier + specifier) where each carries
     * its own [ResolvedBlissComponent.lemma] and empty indicators ready for the
     * indicator-attachment tier.
     *
     * WordNet offset buckets (Princeton WN 3.1):
     * - Nouns:  100 000 000 – 113 999 999
     * - Verbs:  200 000 000 – 202 999 999
     * - Adj:    300 000 000 – 302 999 999
     * - Adv:    400 000 000 – 402 999 999
     */
    private fun stageBStructured(word: String, lang: String): ComposedBlissWord? {
        val (specifierId, resolvedLemma) = resolveSurfaceOrLemma(word) ?: return null
        val specSynset = lookup.synsetOf(specifierId)
        if (specSynset < 0L) return null

        val bucket = wordnetBucket(specSynset)

        var bestId    = -1
        var bestDelta = Long.MAX_VALUE
        for ((bciId, bciSynset) in lookup.synsets) {
            if (wordnetBucket(bciSynset) != bucket) continue
            val delta = kotlin.math.abs(bciSynset - specSynset)
            if (delta < bestDelta) {
                bestDelta = delta
                bestId    = bciId
            }
        }
        if (bestId < 0) return null

        Log.d(TAG, "stageBStructured: '$word' → classifier BCI $bestId, specifier BCI $specifierId, delta=$bestDelta")

        val components: List<ResolvedBlissComponent> = if (bestId != specifierId) {
            val classifierSymbol = lookup.toSymbol(
                id     = bestId,
                source = word,
                lemma  = lookup.nameOf(bestId),
                mt     = BlissSymbol.MatchType.SEMANTIC
            )
            val specifierSymbol = lookup.toSymbol(
                id     = specifierId,
                source = word,
                lemma  = resolvedLemma,
                mt     = BlissSymbol.MatchType.SEMANTIC
            )
            listOf(
                ResolvedBlissComponent(symbol = classifierSymbol, lemma = classifierSymbol.lemma),
                ResolvedBlissComponent(symbol = specifierSymbol,  lemma = resolvedLemma)
            )
        } else {
            val single = lookup.toSymbol(
                id     = bestId,
                source = word,
                lemma  = resolvedLemma,
                mt     = BlissSymbol.MatchType.SEMANTIC
            )
            listOf(ResolvedBlissComponent(symbol = single, lemma = resolvedLemma))
        }

        return ComposedBlissWord(
            sourceWord      = word,
            lemma           = resolvedLemma,
            sourceLang      = lang,
            components      = components,
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

    /**
     * Returns a pair (bciAvId, resolvedLemma) trying surface lookup first,
     * then lemma lookup.  Returns null if both miss.
     */
    private fun resolveSurfaceOrLemma(word: String): Pair<Int, String>? {
        lookup.lookupSurface(word)?.let { return Pair(it, word) }
        lookup.lookupLemma(word)?.let   { return Pair(it, word) }
        return null
    }

    private fun resolveFragment(fragment: String): Int? {
        if (fragment.length < MIN_FRAGMENT_LEN) return null
        return lookup.lookupSurface(fragment) ?: lookup.lookupLemma(fragment)
    }

    /**
     * Returns a coarse bucket identifier for a WordNet synset offset.
     * Used by Stage B to restrict the hypernym search to the same POS class.
     */
    private fun wordnetBucket(synset: Long): Int = when {
        synset in 100_000_000L..113_999_999L -> BUCKET_NOUN
        synset in 200_000_000L..202_999_999L -> BUCKET_VERB
        synset in 300_000_000L..302_999_999L -> BUCKET_ADJ
        synset in 400_000_000L..402_999_999L -> BUCKET_ADV
        else                                 -> BUCKET_OTHER
    }

    companion object {
        private const val TAG              = "BlissSemanticComposer"
        private const val MIN_WORD_LEN     = 6
        private const val MIN_FRAGMENT_LEN = 3

        // WordNet semantic bucket constants
        private const val BUCKET_NOUN  = 1
        private const val BUCKET_VERB  = 2
        private const val BUCKET_ADJ   = 3
        private const val BUCKET_ADV   = 4
        private const val BUCKET_OTHER = 0
    }
}
