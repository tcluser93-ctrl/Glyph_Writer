package com.blueapps.egyptianwriter.bliss

import android.util.Log
import java.util.Locale

/**
 * Tier 3g — Semantic composition helper.
 *
 * Replaces the legacy orthographic pivot-split with a three-stage strategy
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
 * ## Stage A — Direct synset match (WordNet/BlissNet)
 * If the input token can be resolved to a BCI-AV ID (surface or lemma lookup)
 * and that ID has a known WordNet synset offset in `bci_blissnet.json`, the
 * inverted synset index is consulted to return a symbol whose synset directly
 * matches.  This is a pure semantic hit — no new composition, just a reliable
 * cross-lingual link via BlissNet.
 *
 * ## Stage B — Hypernym classifier (semantic bucket)
 * When Stage A misses, the synset offset of the resolved token is mapped to a
 * WordNet semantic bucket (noun coarse-range / verb coarse-range) and compared
 * against the synsets of all BCI IDs in that bucket.  The closest BCI symbol
 * (lowest |synset delta| within the bucket) is used as a **classifier** symbol
 * to build a two-element SEMANTIC composition: `[classifier, specifier]`, where
 * the specifier is the directly-resolved BCI ID if available, or null.
 *
 * ## Stage C — Orthographic pivot-split (legacy fallback, off by default)
 * The original exhaustive pivot-split over grapheme substrings.  Not BCI-
 * conformant but useful for maximising coverage in practice.  Disabled by
 * default via [enableOrthographicFallback].  When enabled, results carry
 * [BlissSymbol.MatchType.COMPOUND] to distinguish them from Stage A/B results
 * that carry [BlissSymbol.MatchType.SEMANTIC].
 *
 * ## Thread-safety
 * This class is stateless after construction (indices are lazy `val`).
 * Safe to share across coroutines.
 *
 * @param lookup                    A ready [BlissLookup] instance.
 * @param enableOrthographicFallback  When `true`, Stage C runs after A+B fail.
 *                                    Defaults to `false` (BCI-clean mode).
 */
class BlissSemanticComposer(
    private val lookup: BlissLookup,
    val enableOrthographicFallback: Boolean = false
) {

    // ── inverted synset index: synsetOffset -> list of BCI-AV IDs ────────────

    /**
     * Built lazily from [BlissLookup.synsets].
     * Maps WordNet synset offset → list of BCI-AV IDs that share it.
     */
    private val synsetToBciIds: Map<Long, List<Int>> by lazy {
        lookup.synsets
            .entries
            .filter { it.value >= 0L }
            .groupBy(keySelector = { it.value }, valueTransform = { it.key })
    }

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
     * Resolves [word] to a BCI-AV ID and checks whether the inverted synset
     * index contains that same synset offset.  Returns a [ComposedBlissWord]
     * with a single [ResolvedBlissComponent] carrying [BlissSymbol.MatchType.SEMANTIC].
     *
     * Re-lemmatisation: if [BlissLookup.lookupSurface] misses but
     * [BlissLookup.lookupLemma] hits, the component's [ResolvedBlissComponent.lemma]
     * records the resolved lemma rather than the surface form.
     */
    private fun stageAStructured(word: String, lang: String): ComposedBlissWord? {
        val (candidateId, resolvedLemma) = resolveSurfaceOrLemma(word) ?: return null
        val synset = lookup.synsetOf(candidateId)
        if (synset < 0L) return null

        val matchId = synsetToBciIds[synset]
            ?.let { ids -> ids.firstOrNull { it == candidateId } ?: ids.firstOrNull() }
            ?: return null

        Log.d(TAG, "stageAStructured: '$word' → BCI $matchId (synset=$synset)")
        val symbol = lookup.toSymbol(
            id     = matchId,
            source = word,
            lemma  = resolvedLemma,
            mt     = BlissSymbol.MatchType.SEMANTIC
        )
        val component = ResolvedBlissComponent(
            symbol     = symbol,
            lemma      = resolvedLemma,
            indicators = symbol.indicators
        )
        return ComposedBlissWord(
            sourceWord       = word,
            lemma            = resolvedLemma,
            sourceLang       = lang,
            components       = listOf(component),
            compositionStage = ComposedBlissWord.Stage.A
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
            sourceWord       = word,
            lemma            = resolvedLemma,
            sourceLang       = lang,
            components       = components,
            compositionStage = ComposedBlissWord.Stage.B
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
                sourceWord       = originalWord,
                lemma            = originalWord,
                sourceLang       = lang,
                components       = listOf(
                    ResolvedBlissComponent(symbol = leftSymbol,  lemma = left),
                    ResolvedBlissComponent(symbol = rightSymbol, lemma = right)
                ),
                compositionStage = ComposedBlissWord.Stage.C
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
