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
 * 4. **Pipeline stage marker** — [compositionStage] records which stage of
 *    [BlissSemanticComposer] produced this result (A, B, or C) for debugging
 *    and analytics.
 *
 * @param sourceWord     Original surface word from user input.
 * @param lemma          Top-level lemma resolved from [sourceWord].
 * @param sourceLang     ISO-639-1 code of the input language.
 * @param components     Ordered list of resolved Bliss components.
 *                       At least one element; classifiers precede specifiers.
 * @param compositionStage  Which composer stage produced this result.
 *                       [Stage.A] = direct synset match,
 *                       [Stage.B] = hypernym classifier,
 *                       [Stage.C] = orthographic pivot-split (legacy).
 */
data class ComposedBlissWord(
    val sourceWord:        String,
    val lemma:             String,
    val sourceLang:        String,
    val components:        List<ResolvedBlissComponent>,
    val compositionStage:  Stage
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
     */
    fun toFlatSymbol(): BlissSymbol {
        if (components.size == 1) return primarySymbol
        return BlissSymbol(
            bciAvId      = BlissSymbol.COMPOUND_SYMBOL_ID,
            name         = components.joinToString("+") { it.symbol.name },
            synsetId     = primarySymbol.synsetId,
            sourceWord   = sourceWord,
            lemma        = lemma,
            matchType    = if (compositionStage == Stage.C)
                               BlissSymbol.MatchType.COMPOUND
                           else
                               BlissSymbol.MatchType.SEMANTIC,
            indicators   = components.flatMap { it.indicators }.distinct(),
            componentIds = components.map { it.symbol.bciAvId }
        )
    }

    // ── nested types ──────────────────────────────────────────────────────────

    /**
     * Records which stage of [BlissSemanticComposer] produced this result.
     */
    enum class Stage {
        /** Direct synset match via inverted BlissNet index. */
        A,
        /** Hypernym classifier via WordNet coarse-bucket proximity. */
        B,
        /** Orthographic pivot-split (legacy opt-in fallback). */
        C
    }
}
