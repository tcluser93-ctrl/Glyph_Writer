# Glyph\_Writer — BlissTranslator

> Bliss symbol AAC translation engine for Android
> Current milestone: **Patch 7** — `main` branch

## What it does

Glyph\_Writer translates free natural-language text (Italian, English, German,
French, Spanish, Dutch, Polish, Portuguese) into sequences of
[Bliss symbols](https://www.blissymbolics.org/) for use in AAC (Augmentative
and Alternative Communication) apps.

## Architecture snapshot (Patch 7)

```
BlissTranslator
 ├─ Tier 3b  MorfologikLemmatizer   FSA-based lemma + POS indicators
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

| Patch | SHA | Description |
|-------|-----|-------------|
| 3 | — | BlissSemanticComposer + COMPOUND single-symbol tier 3g |
| 4 | — | resolveTokenSuspend returns List\<BlissSymbol\> |
| 6 | `e5623ba` | composeStructured(), ResolvedBlissComponent, BlissRenderAttachment, ComposedBlissWord |
| 6-test | `a9751ab` | BlissSemanticComposerTest — Stage A/B/C + shim coverage |
| 7 | `794e3c7` | Tier 3g migrated to composeStructured(); one SEMANTIC symbol per component |
| 7-render | *(this commit)* | BlissRenderer.renderWithAttachments(); SVG overlay drawing; INTEGRATION.md update |

## Key files

| File | Role |
|------|------|
| `BlissTranslator.kt` | 8-tier NLP pipeline |
| `BlissSemanticComposer.kt` | Stage A/B/C composition |
| `ComposedBlissWord.kt` | Structured composer output |
| `ResolvedBlissComponent.kt` | Per-component symbol + attachments |
| `BlissRenderAttachment.kt` | Overlay/linear indicator metadata |
| `BlissRenderer.kt` | SVG render + overlay drawing |
| `BlissLookup.kt` | CSV dictionary + Room FTS4 |
| `MorfologikLemmatizer.kt` | FSA morphological analysis |
| `INTEGRATION.md` | Integration guide (this repo) |

## Running tests

```bash
./gradlew :app:testDebugUnitTest
```

Test suite covers: Stage A/B/C composer, legacy shim, GlyphX builder (core +
SVG), ViewModel async pipeline, lookup CSV loading, translation stats.

## Languages supported

it, en, de, fr, es, nl, pl, pt — Morfologik .dict assets bundled in `assets/`.

## License

See `LICENSE` in repo root.
