# BlissTranslator — Integration Guide

> Last updated: **post-Patch 9** (2026-07-10)

---

## Architecture overview

```
Input text
    ↓
BlissViewModel.translate()
    ↓
BlissTranslator.translateAsync()
    ↓
  Tier 3a  lookupSurface()                     → EXACT
  Tier 3b  MorfologikLemmatizer.analyzeWithTags()
  │        └─ MorfologikTagMapper.toBlissIndicators(rawTag)
  │        └─ lemma + rawTag + blissIndicators   → LEMMA  ✅
  Tier 3c  lookupLemma(word)                   → LEMMA
  Tier 3d  heuristicPos() + lookupLemmaPos()   → LEMMA
  Tier 3e  simpleDeaffix() + lookup            → LEMMA
  Tier 3f  lookupSurfaceDb() Room FTS4         → EXACT
  Tier 4   BlissSemanticComposer
  │        Stage A (4a) synset → SEMANTIC   [CompositionPath.SYNONYM_SYNSET]
  │        Stage B (4b) bucket → COMPOUND   [CompositionPath.SEMANTIC_DECOMPOSITION]
  │        Stage C (4c) ortog  → COMPOUND   [CompositionPath.ORTHOGRAPHIC]
  Tier 3h  UNKNOWN
    ↓
attachIndicators()    ← conforme al target ✓
    ↓
List<BlissSymbol>  (1..N per token da Tier 4)
    ↓
BlissViewModel: resolve ComposedBlissWord? per ogni SEMANTIC symbol
    ↓
  renderMode == CLASSIC              →  BlissGlyphXBuilder → BlissRenderer.render()
  renderMode == STRUCTURED (1 token) →  BlissRenderer.renderWithAttachments()  ✅ Patch 9
  renderMode == STRUCTURED (multi)   →  renderStructuredMultiToken()           ✅ post-P9
    ↓
FlexboxLayout / LinearLayout (chip per classic, SvgCellView per structured)
```

---

## Gap Analysis — stato finale (tutti chiusi)

| GAP | Titolo | Stato |
|---|---|---|
| GAP-1 | MorfologikTagMapper pubblico + rawPosTags | ✅ CLOSED |
| GAP-2 | compositionStage → compositionPath rename | ✅ CLOSED (post-P9) |
| GAP-3 | MatchType.COMPOUND ripristinato | ✅ CLOSED |
| GAP-4 | attachIndicators() conforme | ✅ CLOSED |
| GAP-5 | Naming Tier 3g → Tier 4 | ✅ CLOSED (P9) |
| Fragment dispatch single-token | observeViewModel() STRUCTURED | ✅ CLOSED (P9) |
| Fragment dispatch multi-token | renderStructuredMultiToken() | ✅ CLOSED (post-P9) |
| BlissViewModelTest Patch 8 fields | renderMode, composedWords, semantic | ✅ CLOSED (P9) |
| MorfologikTagMapperTest | test isolato mapper | ✅ CLOSED (post-P9) |

---

## GAP-2 — CompositionPath rename (post-Patch 9)

`ComposedBlissWord.compositionStage: Stage` → `compositionPath: CompositionPath`.

`CompositionPath` è ora una top-level enum:
```kotlin
enum class CompositionPath {
    SYNONYM_SYNSET,         // Tier 4a: synset diretto
    SEMANTIC_DECOMPOSITION, // Tier 4b: classificatore hypernym
    ORTHOGRAPHIC            // Tier 4c: pivot ortografico (legacy)
}
```

Backward-compat: `typealias Stage = CompositionPath` + `@Deprecated compositionStage`
consentono migrazione graduale senza breaking change.

---

## Conformità finale

| Componente | Conformità | Patch |
|---|---|---|
| Tier 3a exact surface | 100% | ✅ |
| Tier 3b Morfologik lemma+tag | 100% | ✅ |
| Tier 3c–3f fallback | 100% | ✅ |
| Tier 4 composer | 100% | ✅ |
| `attachIndicators()` | 100% | ✅ |
| `MorfologikTagMapper` pubblico | 100% | ✅ |
| `MorfologikTagMapperTest` | 100% | ✅ post-P9 |
| Fragment render dispatch single | 100% | ✅ P9 |
| Fragment render dispatch multi | 100% | ✅ post-P9 |
| `BlissViewModelTest` Patch 8 | 100% | ✅ P9 |
| `CompositionPath` rename (GAP-2) | 100% | ✅ post-P9 |

---

## Roadmap post-post-Patch 9

| Priorità | Task | Note |
|---|---|---|
| MEDIA | Unificare container chip+SVG in un unico `MixedBlissRowView` | `renderStructuredMultiToken` attuale appende SVG dopo chip; una view dedicata sarebbe più pulita |
| BASSA | Rimuovere `typealias Stage` e `@Deprecated compositionStage` | Dopo la migrazione di tutti i call-site a `CompositionPath` |
| BASSA | `ComposedBlissWord.Stage.A/B/C` → `CompositionPath.*` in `BlissSemanticComposerTest` | Seguire migration guide nel KDoc |

---

## BlissRenderer — structured path (Patch 7)

`BlissRenderer.renderWithAttachments(container, composedBlissWord)`:
1. Fetches base glyph SVGs per ogni `ResolvedBlissComponent.symbol.bciAvId`
2. Fetches overlay SVGs per ogni `BlissRenderAttachment` con `isOverlay == true`
3. Disegna overlays in `SvgCellView.drawOverlays()` a offset scalati per densità
4. Aggiunge modificatori lineari (`isOverlay == false`) come celle adiacenti

Overlay size = `OVERLAY_SIZE_RATIO (0.35)` × cell width.
Modifier size = `MODIFIER_SIZE_RATIO (0.55)` × cell width.
Default Y offset = `BlissRenderAttachment.DEFAULT_OVERLAY_Y_OFFSET_PX = -14f`.

---

## MatchType reference

| MatchType | Source | `TranslationStats` field |
|---|---|---|
| EXACT | Surface / FTS4 | `exact` |
| NGRAM | Multi-word phrase | `ngram` |
| LEMMA | Morfologik / CSV lemma | `lemma` |
| SEMANTIC | Tier 4a (SYNONYM_SYNSET) | `semantic` |
| COMPOUND | Tier 4b/4c (SEMANTIC_DECOMPOSITION/ORTHOGRAPHIC) | — |
| UNKNOWN | Nessun match | `unknown` |
| FALLBACK_CATEGORY | Categoria broad | — |

---

## TranslationStats

```kotlin
data class TranslationStats(
    val total:    Int,
    val exact:    Int,
    val lemma:    Int,
    val ngram:    Int,
    val semantic: Int,   // SEMANTIC (Tier 4a)
    val unknown:  Int
)
```

---

## Indicator BCI ids

| Indicator | BCI id | Bliss name |
|---|---|---|
| plural | 9011 | indicator_plural |
| past | 9007 | indicator_past |
| future | 9008 | indicator_future |

---

## Test coverage

| Test file | Copre | Stato |
|---|---|---|
| `BlissSemanticComposerTest.kt` | Stage A/B/C; shim; overlays | ✅ |
| `BlissLookupTest.kt` | CSV loading, lemma/surface/pos | ✅ |
| `BlissGlyphXBuilderCoreTest.kt` | GlyphX Document | ✅ |
| `BlissGlyphXBuilderSvgTest.kt` | SVG export | ✅ |
| `BlissViewModelTest.kt` | UiState, Stats, renderMode, composedWords | ✅ |
| `MorfologikTagMapperTest.kt` | toBlissIndicators: null/plural/past/future/mixed | ✅ post-P9 |
