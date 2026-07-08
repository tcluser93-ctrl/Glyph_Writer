# Glyph\_Writer — BlissTranslator

> Bliss symbol AAC translation engine for Android
> Current milestone: **Patch 8** — `main` branch

## What it does

Glyph\_Writer translates free natural-language text (Italian, English, German,
French, Spanish, Dutch, Polish, Portuguese) into sequences of
[Bliss symbols](https://www.blissymbolics.org/) for use in AAC
(Augmentative and Alternative Communication) apps.

## Architecture snapshot (Patch 8)

```
BlissViewModel
 ├─ setLang()         wires BlissSemanticComposer → BlissTranslator (Patch 8)
 ├─ translate()       resolves ComposedBlissWord? per SEMANTIC token (Patch 8)
 ├─ UiState
 │    ├─ composedWords   List<ComposedBlissWord?>  (Patch 8)
 │    └─ renderMode      CLASSIC | STRUCTURED       (Patch 8)
 └─ TranslationStats   + semantic counter           (Patch 8)

BlissTranslator
 ├─ Tier 3b  MorfologikLemmatizer   FSA lemma + POS indicators
 └─ Tier 3g  BlissSemanticComposer   composeStructured() → ComposedBlissWord
               ├─ Stage A  direct synset hit
               ├─ Stage B  bucket-based composition
               └─ Stage C  orthographic fallback (disabled by default)

BlissRenderer
 ├─ render()                  classic GlyphX Document path
 └─ renderWithAttachments()   structured ComposedBlissWord path (Patch 7)
      ├─ base glyph SVG per ResolvedBlissComponent
      ├─ overlay indicators   drawn via drawOverlays() at density-scaled offset
      └─ linear modifiers     adjacent SvgCellView cells
```

## Patch history

| Patch | Commit SHA | Description |
|-------|-----------|-------------|
| 3 | — | `BlissSemanticComposer` + COMPOUND single-symbol tier 3g |
| 4 | — | `resolveTokenSuspend` returns `List<BlissSymbol>` |
| 6 | `e5623ba` | `composeStructured()`, `ResolvedBlissComponent`, `BlissRenderAttachment`, `ComposedBlissWord` |
| 6-test | `a9751ab` | `BlissSemanticComposerTest` — Stage A/B/C + shim coverage |
| 7 | `794e3c7` | Tier 3g → `composeStructured()`; N `SEMANTIC` symbols per token |
| 7-render | `6dc2d84` | `BlissRenderer.renderWithAttachments()`; SVG overlay drawing; INTEGRATION.md |
| **8** | *(this commit)* | `BlissViewModel`: composer wired at runtime, render dispatch, `TranslationStats.semantic`, `UiState.composedWords`/`renderMode` |

## Key files

| File | Role | Last changed |
|------|------|-------------|
| `BlissViewModel.kt` | Translate orchestration + render dispatch | **Patch 8** |
| `BlissTranslator.kt` | 8-tier NLP pipeline | Patch 7 |
| `BlissSemanticComposer.kt` | Stage A/B/C composition | Patch 6 |
| `ComposedBlissWord.kt` | Structured composer output | Patch 6 |
| `ResolvedBlissComponent.kt` | Per-component symbol + attachments | Patch 6 |
| `BlissRenderAttachment.kt` | Overlay/linear indicator metadata | Patch 6 |
| `BlissRenderer.kt` | SVG render + overlay drawing | Patch 7-render |
| `BlissLookup.kt` | CSV dictionary + Room FTS4 | — |
| `MorfologikLemmatizer.kt` | FSA morphological analysis | — |
| `INTEGRATION.md` | Integration guide | **Patch 8** |

## Running tests

```bash
./gradlew :app:testDebugUnitTest
```

Test suite: Stage A/B/C composer, legacy shim, GlyphX builder (core + SVG),
ViewModel async pipeline, lookup CSV loading, translation stats.

> **TODO (Patch 8)**: `BlissViewModelTest` must be updated to assert
> `UiState.renderMode`, `UiState.composedWords`, and `TranslationStats.semantic`.

## Languages supported

it, en, de, fr, es, nl, pl, pt — Morfologik `.dict` assets in `assets/`.

## License

See `LICENSE` in repo root.
