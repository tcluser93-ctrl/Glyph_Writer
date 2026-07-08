# BlissTranslator — Integration Guide

> Last updated: **Patch 8** (2026-07-09)

## Architecture overview

```
Input text
    ↓
BlissViewModel.translate()
    ↓
BlissTranslator.translateAsync()
    ↓
  Tier 3a  Exact surface lookup             → EXACT
  Tier 3b  MorfologikLemmatizer             → LEMMA  (+ per-token indicators)
  Tier 3c  Plain lemma lookup               → LEMMA
  Tier 3d  POS heuristic + CSV              → LEMMA
  Tier 3e  De-affixation                    → LEMMA
  Tier 3f  Room FTS4                        → EXACT
  Tier 3g  BlissSemanticComposer            → SEMANTIC  ← Patch 7/8
  Tier 3h  UNKNOWN
    ↓
List<BlissSymbol>  (1..N per input token, SEMANTIC tokens = N components)
    ↓
BlissViewModel: resolve ComposedBlissWord? for each SEMANTIC symbol ← Patch 8
    ↓
  renderMode == CLASSIC      →  BlissGlyphXBuilder → BlissRenderer.render()
  renderMode == STRUCTURED   →  BlissRenderer.renderWithAttachments()  ← Patch 7/8
    ↓
LinearLayout (one SvgCellView per symbol / component)
```

## Tier 3g — Semantic composition (Patch 7)

`BlissSemanticComposer.composeStructured(word, lang)` returns a `ComposedBlissWord`:

- `compositionStage` — A (direct synset), B (bucket), C (orthographic, off by default)
- `components: List<ResolvedBlissComponent>` — one entry per semantic fragment
- Each component carries `renderAttachments: List<BlissRenderAttachment>` with
  `isOverlay`, `xOffsetPx`, `yOffsetPx`, `priority`, `bciIndicatorId`

In `BlissTranslator` tier 3g, `composeStructured()` expands one input token into
`N` `BlissSymbol(MatchType.SEMANTIC)` items — one per component.

## BlissViewModel render dispatch (Patch 8)

`BlissViewModel` now wires `BlissSemanticComposer` into `BlissTranslator` at
`setLang()` so tier 3g is active at runtime (not just in tests).

After translation, the ViewModel checks each `BlissSymbol.matchType`:
- If any symbol has `MatchType.SEMANTIC`, `composeStructured()` is called again
  on `symbol.lemma` to recover the full `ComposedBlissWord` with `renderAttachments`.
- `UiState.composedWords: List<ComposedBlissWord?>` stores the per-token result
  (null for non-semantic tokens).
- `UiState.renderMode` is set to `STRUCTURED` if any entry is non-null,
  `CLASSIC` otherwise.

**Fragment responsibility** (not yet updated):
```kotlin
when (uiState.renderMode) {
    RenderMode.STRUCTURED -> {
        val composed = uiState.composedWords.firstOrNull { it != null }!!
        renderer.renderWithAttachments(container, composed)
    }
    RenderMode.CLASSIC -> renderer.render(container, uiState.glyphXDoc!!)
}
```

## BlissRenderer — structured path (Patch 7)

`BlissRenderer.renderWithAttachments(container, composedBlissWord)`:

1. Fetches base glyph SVGs for each `ResolvedBlissComponent.bciSymbolId`
2. Fetches overlay SVGs for each `BlissRenderAttachment` where `isOverlay == true`
3. Draws overlays in `SvgCellView.drawOverlays()` at density-scaled offsets
4. Adds linear modifiers (`isOverlay == false`) as separate adjacent cells

Overlay size = `OVERLAY_SIZE_RATIO (0.35)` × cell width.
Modifier size = `MODIFIER_SIZE_RATIO (0.55)` × cell width.
Default Y offset for combining indicators = `BlissRenderAttachment.DEFAULT_OVERLAY_Y_OFFSET_PX = -14f`.

## MatchType reference

| MatchType         | Source                                 | Counted in stats |
|-------------------|----------------------------------------|------------------|
| EXACT             | Surface lexicon JSON / FTS4 DB         | `exact`          |
| NGRAM             | Multi-word phrase lookup               | `ngram`          |
| LEMMA             | Morfologik FSA / CSV lemma             | `lemma`          |
| SEMANTIC          | BlissSemanticComposer tier 3g          | `semantic` (P8)  |
| COMPOUND          | Legacy, removed in Patch 7             | —               |
| UNKNOWN           | No match in any tier                   | `unknown`        |
| FALLBACK_CATEGORY | Broad category fallback                | —               |

## TranslationStats (Patch 8)

```kotlin
data class TranslationStats(
    val total:    Int,   // all symbols
    val exact:    Int,   // MatchType.EXACT
    val lemma:    Int,   // MatchType.LEMMA
    val ngram:    Int,   // MatchType.NGRAM
    val semantic: Int,   // MatchType.SEMANTIC  ← NEW Patch 8
    val unknown:  Int    // MatchType.UNKNOWN
)
// coverage = (total - unknown) / total
```

## UiState new fields (Patch 8)

| Field          | Type                        | Description |
|----------------|-----------------------------|-------------|
| `composedWords`| `List<ComposedBlissWord?>`  | Per-token structured result; null = non-semantic token |
| `renderMode`   | `RenderMode`                | `CLASSIC` or `STRUCTURED` |

## Instantiation example

```kotlin
// Engine wiring (Patch 8 — now done inside BlissViewModel.setLang):
val composer   = BlissSemanticComposer(blissLookup)
val translator = BlissTranslator(
    lookup     = blissLookup,
    morfologik = MorfologikLemmatizer(context, "it"),
    composer   = composer       // tier 3g active
)

// After translateAsync(), in Fragment:
when (uiState.renderMode) {
    RenderMode.STRUCTURED -> {
        val composed = uiState.composedWords.firstOrNull { it != null }!!
        renderer.renderWithAttachments(container, composed)
    }
    RenderMode.CLASSIC -> renderer.render(container, uiState.glyphXDoc!!)
}
```

## Test coverage (Patch 8)

| Test file                        | Covers                                       |
|----------------------------------|----------------------------------------------|
| `BlissSemanticComposerTest.kt`   | Stage A/B/C; legacy shim; overlays          |
| `BlissViewModelTest.kt`          | Async translation pipeline (needs update)   |
| `BlissLookupTest.kt`             | CSV loading, lemma/surface/pos lookup       |
| `BlissGlyphXBuilderCoreTest.kt`  | GlyphX Document structure                  |
| `BlissGlyphXBuilderSvgTest.kt`   | SVG export correctness                      |

> **TODO**: `BlissViewModelTest` must be updated to verify `UiState.renderMode`,
> `UiState.composedWords`, and `TranslationStats.semantic` for Patch 8 coverage.

## Indicator BCI ids

| Indicator | BCI id | Bliss name         |
|-----------|--------|--------------------|
| plural    | 9011   | indicator_plural   |
| past      | 9007   | indicator_past     |
| future    | 9008   | indicator_future   |
