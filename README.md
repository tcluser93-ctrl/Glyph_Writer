# Glyph\_Writer — BlissTranslator

> Bliss symbol AAC translation engine for Android
> Current milestone: **Patch 8 + Gap Analysis** — `main` branch

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
 ├─ Tier 3a  lookupSurface()         EXACT
 ├─ Tier 3b  MorfologikLemmatizer    LEMMA + per-token indicators
 │    [ GAP-1: rawPosTags non esposto — Patch 9 ]
 ├─ Tier 3c–3f  fallback lookups
 └─ Tier 3g / 4  BlissSemanticComposer
               ├─ Stage A / 4a  synset      → SEMANTIC
               ├─ Stage B / 4b  bucket      → SEMANTIC  [ GAP-3: should be COMPOUND ]
               └─ Stage C       orthographic (off)

BlissRenderer
 ├─ render()                  classic GlyphX path
 └─ renderWithAttachments()   ComposedBlissWord path (Patch 7)
      ├─ overlay indicators   density-scaled SVG drawOverlays()
      └─ linear modifiers     adjacent SvgCellView cells
```

## Gap vs. target architetturale

La struttura proposta ha evidenziato **4 gap** rispetto allo stato `main`.
Vedi `INTEGRATION.md` per la specifica completa.

| Gap | Severità | Patch |
|-----|---------|-------|
| GAP-1: `MorfologikTagMapper` non estratto; `rawPosTags` non esposto in `LemmaAnalysis` | ALTA | 9 |
| GAP-2: `compositionStage` non mappa esplicitamente Tier 4a/4b | MEDIA | 9 |
| GAP-3: `MatchType.COMPOUND` rimosso in P7, Stage B usa `SEMANTIC` | ALTA | 9 |
| GAP-4: `attachIndicators()` | **CONFORME** | ✔ |
| GAP-5: naming "Tier 3g" vs. "Tier 4" in docs | BASSA | 9 |

## Patch history

| Patch | Commit SHA | Description |
|-------|-----------|-------------|
| 3 | — | `BlissSemanticComposer` + COMPOUND single-symbol tier 3g |
| 4 | — | `resolveTokenSuspend` returns `List<BlissSymbol>` |
| 6 | `e5623ba` | `composeStructured()`, `ResolvedBlissComponent`, `BlissRenderAttachment`, `ComposedBlissWord` |
| 6-test | `a9751ab` | `BlissSemanticComposerTest` — Stage A/B/C + shim |
| 7 | `794e3c7` | Tier 3g → `composeStructured()`; N `SEMANTIC` per token; COMPOUND rimosso |
| 7-render | `6dc2d84` | `BlissRenderer.renderWithAttachments()`; SVG overlay; INTEGRATION.md |
| 8 | `9ccbdb9` | `BlissViewModel`: composer wired, render dispatch, `TranslationStats.semantic`, `UiState.composedWords`/`renderMode` |
| **8-gap** | *(this commit)* | Gap analysis vs. target: GAP-1–5 documentati, roadmap Patch 9 in INTEGRATION.md |

## Key files

| File | Role | Last changed |
|------|------|-------------|
| `BlissViewModel.kt` | Translate orchestration + render dispatch | Patch 8 |
| `BlissTranslator.kt` | 8-tier NLP pipeline | Patch 7 |
| `BlissSemanticComposer.kt` | Stage A/B/C composition | Patch 6 |
| `ComposedBlissWord.kt` | Structured composer output | Patch 6 |
| `ResolvedBlissComponent.kt` | Per-component symbol + attachments | Patch 6 |
| `BlissRenderAttachment.kt` | Overlay/linear indicator metadata | Patch 6 |
| `BlissRenderer.kt` | SVG render + overlay drawing | Patch 7-render |
| `BlissLookup.kt` | CSV dictionary + Room FTS4 | — |
| `MorfologikLemmatizer.kt` | FSA morphological analysis | — |
| `MorfologikTagMapper.kt` | *TODO Patch 9 — da creare (GAP-1)* | — |
| `INTEGRATION.md` | Integration guide + gap analysis | **Patch 8-gap** |

## Running tests

```bash
./gradlew :app:testDebugUnitTest
```

Suite: Stage A/B/C composer, GlyphX builder, ViewModel, lookup CSV, stats.

> **TODO (Patch 9)**:
> - `BlissViewModelTest`: assertire `renderMode`, `composedWords`, `TranslationStats.semantic`
> - `MorfologikTagMapperTest`: nuovo test per il mapper estratto (GAP-1)

## Languages supported

it, en, de, fr, es, nl, pl, pt — Morfologik `.dict` in `assets/`.

## License

See `LICENSE` in repo root.
