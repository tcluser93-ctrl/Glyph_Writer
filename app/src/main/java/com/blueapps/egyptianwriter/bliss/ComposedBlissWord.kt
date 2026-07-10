package com.blueapps.egyptianwriter.bliss

/**
 * The structured output of [BlissSemanticComposer.composeStructured].
 *
 * ## Patch 6 — Motivation
 * Before Patch 6, the composer returned a single [BlissSymbol] even for
 * multi-component compositions; per-component lemma, POS, indicators and
 * render metadata were all discarded.  [ComposedBlissWord] replaces that
 * flat representation with an ordered list of [ResolvedBlissComponent]s,
 * each carrying its own symbol, lemma, POS, indicators and overlay hints.
 *
 * ## Design principles
 * 1. **Immutable value object** — all fields are `val`; mutations produce copies.
 * 2. **Language-agnostic** — [sourceLang] is informational; the components hold
 *    only the resolved Bliss data, not language-specific surface forms.
 * 3. **Backward-compatible shim** — [toFlatSymbol] collapses the structure
 *    into the legacy [BlissSymbol] shape expected by existing callers that
 *    have not yet been migrated to the structured API.
 * 4. **Pipeline stage marker** — [compositionPath] records which stage of
 *    [BlissSemanticComposer] produced this result for debugging and analytics.
 *
 * ## GAP-2 (post-Patch 9) — Rename
 * `compositionStage: Stage` → `compositionPath: CompositionPath`.
 * The old `Stage` enum is preserved as a `typealias` so existing call-sites
 * that reference `ComposedBlissWord.Stage.A/B/C` continue to compile without
 * changes during migration; it will be removed in a future cleanup patch.
 *
 * @param sourceWord       Original surface word from user input.
 * @param lemma            Top-level lemma resolved from [sourceWord].
 * @param sourceLang       ISO-639-1 code of the input language.
 * @param components       Ordered list of resolved Bliss components.
 *                         At least one element; classifiers precede specifiers.
 * @param compositionPath  Which composer stage produced this result.
 *                         [CompositionPath.SYNONYM_SYNSET]         = direct synset match (Stage A),
 *                         [CompositionPath.SEMANTIC_DECOMPOSITION] = hypernym classifier (Stage B),
 *                         [CompositionPath.ORTHOGRAPHIC]           = orthographic pivot-split (Stage C).
 */
data class ComposedBlissWord(
    val sourceWord:        String,
    val lemma:             String,
    val sourceLang:        String,
    val components:        List<ResolvedBlissComponent>,
    val compositionPath:   CompositionPath
) {
    init {
        require(components.isNotEmpty()) {
            "ComposedBlissWord must contain at least one component (sourceWord='$sourceWord')"
        }
    }

    /** True when every component resolved to a real BCI symbol. */
    val isFullyResolved: Boolean get() = components.all { it.isResolved }

    /** True when any component carries an overlay indicator. */
    val hasOverlayIndicators: Boolean get() = components.any { it.hasOverlay }

    /** Convenience: the primary (first) symbol — typically classifier or direct match. */
    val primarySymbol: BlissSymbol get() = components.first().symbol

    /**
     * Collapses this structured representation into the legacy [BlissSymbol]
     * shape for backward compatibility with callers that have not yet been
     * migrated to [ComposedBlissWord].
     *
     * - Single-component results: returns [primarySymbol] directly.
     * - Multi-component results: returns a synthetic [BlissSymbol] with
     *   [BlissSymbol.MatchType.SEMANTIC] and [componentIds] populated.
     *
     * MatchType mapping:
     * - [CompositionPath.SYNONYM_SYNSET]         → [BlissSymbol.MatchType.SEMANTIC]
     * - [CompositionPath.SEMANTIC_DECOMPOSITION] → [BlissSymbol.MatchType.SEMANTIC]
     * - [CompositionPath.ORTHOGRAPHIC]           → [BlissSymbol.MatchType.COMPOUND] (legacy opt-in)
     */
    fun toFlatSymbol(): BlissSymbol {
        if (components.size == 1) return primarySymbol
        return BlissSymbol(
            bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
            name         = components.joinToString("+") { it.symbol.name },
            synsetId     = primarySymbol.synsetId,
            sourceWord   = sourceWord,
            lemma        = lemma,
            matchType    = if (compositionPath == CompositionPath.ORTHOGRAPHIC)
                               BlissSymbol.MatchType.COMPOUND
                           else
                               BlissSymbol.MatchType.SEMANTIC,
            indicators   = components.flatMap { it.indicators }.distinct(),
            componentIds = components.map { it.symbol.bciAvId }
        )
    }

    // ── Backward-compat accessor (deprecated) ─────────────────────────────────
    /** @deprecated Use [compositionPath] instead. Will be removed in a future patch. */
    @Deprecated(
        message = "Use compositionPath instead",
        replaceWith = ReplaceWith("compositionPath")
    )
    val compositionStage: CompositionPath get() = compositionPath
}

/**
 * Records which stage of [BlissSemanticComposer] produced a [ComposedBlissWord].
 *
 * ## GAP-2 (post-Patch 9)
 * Renamed from `ComposedBlissWord.Stage` to top-level `CompositionPath`
 * with explicit semantic names aligned to the target architecture (Tier 4a/4b/4c).
 */
enum class CompositionPath {
    /** Tier 4a — direct synset match via inverted BlissNet index. */
    SYNONYM_SYNSET,
    /** Tier 4b — hypernym classifier via WordNet coarse-bucket proximity. */
    SEMANTIC_DECOMPOSITION,
    /** Tier 4c — orthographic pivot-split (legacy opt-in fallback). */
    ORTHOGRAPHIC
}

/**
 * Backward-compatibility typealias.
 * Existing call-sites using `ComposedBlissWord.Stage.A/B/C` will continue to
 * compile; migrate to [CompositionPath] constants at your convenience.
 */
@Deprecated(
    message = "Use CompositionPath enum directly",
    replaceWith = ReplaceWith("CompositionPath", "com.blueapps.egyptianwriter.bliss.CompositionPath")
)
typealias Stage = CompositionPath
