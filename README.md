# Glyph\_Writer — BlissTranslator

> Bliss symbol AAC translation engine for Android
> Current milestone: **Patch 14** — `main` branch

## What it does

Glyph\_Writer translates free natural-language text (Italian, English, German,
French, Spanish, Dutch, Polish, Portuguese) into sequences of
[Bliss symbols](https://www.blissymbolics.org/) for use in AAC
(Augmentative and Alternative Communication) apps.

## Architecture snapshot (Patch 14)

```
BlissViewModel
 ├─ setLang()         wires BlissSemanticComposer → BlissTranslator
 ├─ translate()       resolves ComposedBlissWord? per SEMANTIC token
 ├─ UiState
 │    ├─ composedWords   List<ComposedBlissWord?>
 │    └─ renderMode      CLASSIC | STRUCTURED
 └─ TranslationStats   + semantic counter

BlissTranslator
 ├─ Tier 3a  lookupSurface()         EXACT
 ├─ Tier 3b  MorfologikLemmatizer    LEMMA + rawPosTags + blissIndicators  ✅
 │    └─ MorfologikTagMapper.toBlissIndicators()  (GAP-1 CLOSED)
 ├─ Tier 3c–3f  fallback lookups
 └─ Tier 4  BlissSemanticComposer
               ├─ Stage A (4a) synset → SEMANTIC   [CompositionPath.SYNONYM_SYNSET]
               ├─ Stage B (4b) bucket → COMPOUND   [CompositionPath.SEMANTIC_DECOMPOSITION]
               └─ Stage C (4c) ortog  → COMPOUND   [CompositionPath.ORTHOGRAPHIC]

BlissRenderer
 ├─ render()                  classic GlyphX path
 └─ renderWithAttachments()   ComposedBlissWord path
      ├─ overlay indicators   density-scaled SVG drawOverlays()
      └─ linear modifiers     adjacent SvgCellView cells

BlissTranslateFragment
 ├─ TranslationTriggerMode
 │    ├─ MANUAL_SENTENCE   → btnTranslate
 │    └─ AUTO_PROGRESSIVE  → debounce legacy
 ├─ renderMode == CLASSIC              → BlissGlyphXBuilder → BlissRenderer.render()
 ├─ renderMode == STRUCTURED (single)  → BlissRenderer.renderWithAttachments()  ✅ P9
 └─ renderMode == STRUCTURED (multi)   → MixedBlissRowView.bind() + svgContainerFor()  ✅ P11/P14

BlissTranslateFragment — CAA card view
 ├─ Vista CARDS come modalità predefinita
 ├─ spanCount letto da AppPreferences.getBlissCardsPerRow()  [PR #7 — Patch bliss-ui]
 └─ onResume() aggiorna spanCount dinamicamente senza ricreare il Fragment
```

## Gap vs. target architetturale

Tutti i gap identificati a Patch 8 sono stati chiusi.
Vedi `INTEGRATION.md` per la specifica completa.

| Gap | Severità | Stato |
|-----|---------|-------|
| GAP-1: `MorfologikTagMapper` non estratto; `rawPosTags` non esposto | ALTA | ✅ CLOSED (P9) |
| GAP-2: `compositionStage` → `CompositionPath` rename | MEDIA | ✅ CLOSED (post-P9) |
| GAP-3: `MatchType.COMPOUND` ripristinato | ALTA | ✅ CLOSED (P9) |
| GAP-4: `attachIndicators()` | **CONFORME** | ✅ |
| GAP-5: naming "Tier 3g" vs. "Tier 4" in docs | BASSA | ✅ CLOSED (P9) |

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
| 8-gap | — | Gap analysis vs. target: GAP-1–5 documentati, roadmap Patch 9 in INTEGRATION.md |
| 9 | `ad08cf4` | `TranslationTriggerMode`, `MorfologikTagMapper`, GAP-1–5 chiusi, `BlissViewModelTest` Patch 8 fields |
| 10 | — | `MixedBlissRowView` — container unificato chip+SVG multi-token |
| 11 | — | Fragment integra `MixedBlissRowView`; `renderStructuredMultiToken()` rimosso |
| 12 | — | `MixedBlissRowView` nel layout XML (`fragment_translate.xml`) |
| 13 | — | Fix alias/campi: `sourceToken→sourceWord`, `it.symbol.label→component.symbol.name` |
| **14** | *(latest)* | Fix cast `FrameLayout` in `renderMixedRow` — `svgContainerFor()` tipizzato correttamente |
| **bliss-ui** | PR [#7](https://github.com/tcluser93-ctrl/Glyph_Writer/pull/7) | `bliss_cards_per_row`: card Bliss per riga configurabili (2–8, default 4) |

## Stato attuale

- `main` è stabile alla **Patch 14** (commit `ad08cf4` come base squash, poi Patch 10–14)
- La [PR #7](https://github.com/tcluser93-ctrl/Glyph_Writer/pull/7) introduce `bliss_cards_per_row` — nuova preferenza utente per impostare il numero di card Bliss per riga nella vista `CARDS`, range `2–8`, default `4`, persistenza in `AppPreferences`, controllo tramite `Slider` Material 3 in `SettingsFragment`, aggiornamento dinamico dello `spanCount` del `GridLayoutManager` in `BlissTranslateFragment.onResume()`
- Tutti i GAP della gap analysis Patch 8 sono stati chiusi
- `MixedBlissRowView` è il container definitivo per il rendering multi-token `STRUCTURED`

## Key files

| File | Role | Last changed |
|------|------|-------------|
| `BlissViewModel.kt` | Translate orchestration + render dispatch | Patch 9 |
| `BlissTranslator.kt` | 8-tier NLP pipeline | Patch 7 |
| `BlissSemanticComposer.kt` | Stage A/B/C composition | Patch 13 (alias fix) |
| `ComposedBlissWord.kt` | Structured composer output | post-P9 (CompositionPath) |
| `ResolvedBlissComponent.kt` | Per-component symbol + attachments | Patch 6 |
| `BlissRenderAttachment.kt` | Overlay/linear indicator metadata | Patch 6 |
| `BlissRenderer.kt` | SVG render + overlay drawing | Patch 7-render |
| `MixedBlissRowView.kt` | Container unificato chip+SVG multi-token | Patch 10 |
| `BlissTranslateFragment.kt` | Fragment render dispatch + CAA view | Patch 14 |
| `SettingsFragment.kt` | Preferenze utente incl. `bliss_cards_per_row` | PR #7 |
| `AppPreferences.kt` | Persistenza preferenze | PR #7 |
| `BlissLookup.kt` | CSV dictionary + Room FTS4 | — |
| `MorfologikLemmatizer.kt` | FSA morphological analysis | — |
| `MorfologikTagMapper.kt` | Tag FSA → Bliss indicators | Patch 9 |
| `INTEGRATION.md` | Integration guide + gap analysis + roadmap | Patch 14 |

## Running tests

```bash
./gradlew :app:testDebugUnitTest
```

Suite: Stage A/B/C composer, GlyphX builder, ViewModel, lookup CSV, stats, MorfologikTagMapper.

## Languages supported

it, en, de, fr, es, nl, pl, pt — Morfologik `.dict` in `assets/`.

## License

See `LICENSE` in repo root.
