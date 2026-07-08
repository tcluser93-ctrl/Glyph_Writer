# BlissTranslator — Integration Guide

> Last updated: Patch 7 (2026-07-09)

## Architecture overview

```
Input text
    ↓
BlissTranslator.translateAsync()
    ↓
  Tier 3a  Exact surface lookup             → EXACT
  Tier 3b  MorfologikLemmatizer             → LEMMA  (+ per-token indicators)
  Tier 3c  Plain lemma lookup               → LEMMA
  Tier 3d  POS heuristic + CSV              → LEMMA
  Tier 3e  De-affixation                    → LEMMA
  Tier 3f  Room FTS4                        → EXACT
  Tier 3g  BlissSemanticComposer            → SEMANTIC  ← Patch 7
  Tier 3h  UNKNOWN
    ↓
List<BlissSymbol>  (one or more per input token from Patch 7 tier 3g)
    ↓
BlissGlyphXBuilder.buildDocument()    (classic GlyphX path)
  or
BlissRenderer.renderWithAttachments() (structured path, Patch 7)
    ↓
LinearLayout (SvgCellView per symbol)
```

## Tier 3g — Semantic composition (Patch 7)

`BlissSemanticComposer.composeStructured(word, lang)` replaces the old
`compose()` single-symbol path.  It returns a `ComposedBlissWord` with:

- `compositionStage` — A (direct synset), B (bucket match), or C (orthographic,
  disabled by default)
- `components: List<ResolvedBlissComponent>` — one entry per semantic fragment
- Each component carries `renderAttachments: List<BlissRenderAttachment>` with
  `isOverlay`, `xOffsetPx`, `yOffsetPx`, `priority`, `bciIndicatorId`

In `BlissTranslator` tier 3g, `composeStructured()` expands a single input token
into `N` `BlissSymbol(MatchType.SEMANTIC)` items — one per component.

## BlissRenderer — structured path

Use `BlissRenderer.renderWithAttachments(container, composedBlissWord)` when
you have a `ComposedBlissWord` directly (e.g. from tier 3g).  This path:

1. Fetches base glyph SVGs for each `ResolvedBlissComponent.bciSymbolId`
2. Fetches overlay SVGs for each `BlissRenderAttachment` where `isOverlay == true`
3. Draws overlays in `SvgCellView.drawOverlays()` at density-scaled offsets
4. Adds linear modifiers (`isOverlay == false`) as separate adjacent `SvgCellView`
   cells after the base glyph

Overlay size = `OVERLAY_SIZE_RATIO (0.35)` × cell width.
Modifier size = `MODIFIER_SIZE_RATIO (0.55)` × cell width.
Default Y offset for combining indicators = `BlissRenderAttachment.DEFAULT_OVERLAY_Y_OFFSET_PX = -14f` (pre-density-scale).

## MatchType reference

| MatchType  | Source                                   |
|------------|------------------------------------------|
| EXACT      | Surface lexicon JSON / FTS4 DB           |
| NGRAM      | Multi-word phrase lookup                 |
| LEMMA      | Morfologik FSA / CSV lemma               |
| SEMANTIC   | BlissSemanticComposer (Patch 7)          |
| COMPOUND   | Legacy (removed in Patch 7)              |
| UNKNOWN    | No match found in any tier               |
| FALLBACK_CATEGORY | Broad category fallback           |

## Instantiation example

```kotlin
val translator = BlissTranslator(
    lookup     = blissLookup,
    morfologik = MorfologikLemmatizer(context, "it"),
    composer   = BlissSemanticComposer(blissLookup)
)

// Async (recommended, uses full 3a–3g pipeline)
val symbols: List<BlissSymbol> = translator.translateAsync("le case erano grandi")

// Render via structured path when composer result is available directly:
val composed: ComposedBlissWord? = composer.composeStructured("camminando", "it")
if (composed != null) renderer.renderWithAttachments(container, composed)
else renderer.render(container, glyphXBuilder.buildDocument(symbols))
```

## Test coverage (Patch 7)

| Test file                        | Covers                                    |
|----------------------------------|-------------------------------------------|
| `BlissSemanticComposerTest.kt`   | Stage A, B, C; legacy shim; overlays     |
| `BlissLookupTest.kt`             | CSV loading, lemma/surface/pos lookup    |
| `BlissViewModelTest.kt`          | Async translation pipeline               |
| `BlissGlyphXBuilderCoreTest.kt`  | GlyphX Document structure               |
| `BlissGlyphXBuilderSvgTest.kt`   | SVG export correctness                   |

## Indicator BCI ids

| Indicator | BCI id | Bliss name     |
|-----------|--------|----------------|
| plural    | 9011   | indicator_plural |
| past      | 9007   | indicator_past   |
| future    | 9008   | indicator_future |
