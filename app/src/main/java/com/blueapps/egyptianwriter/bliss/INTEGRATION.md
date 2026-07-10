# BlissTranslator — Integration Guide

> Last updated: **Patch 9** (2026-07-10)

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
  │        └─ lemma + blissIndicators            → LEMMA  (per-token indicators)
  │        [ GAP-1: ✅ CLOSED — MorfologikTagMapper è object pubblico;
  │                              LemmaAnalysis espone rawTag: String? ]
  Tier 3c  lookupLemma(word)                   → LEMMA
  Tier 3d  heuristicPos() + lookupLemmaPos()   → LEMMA
  Tier 3e  simpleDeaffix() + lookup            → LEMMA
  Tier 3f  lookupSurfaceDb() Room FTS4         → EXACT
  Tier 4 (ex 3g)  BlissSemanticComposer
  │        Solo se tutti i tier precedenti → UNKNOWN
  │        Stage A (4a) synset diretto        → SEMANTIC
  │        [ GAP-3: ✅ CLOSED — MatchType.COMPOUND presente in BlissSymbol.MatchType;
  │                              Stage B → COMPOUND, Stage A → SEMANTIC ]
  │        Stage B (4b) scomposizione bucket  → COMPOUND
  │        Stage C (4c) ortografico (off)     → SEMANTIC
  │        [ GAP-2: compositionStage usa Stage A/B/C — KDoc è preciso;
  │                  rinominare a CompositionPath è opzionale (bassa priorità) ]
  Tier 3h  UNKNOWN
    ↓
attachIndicators()    ← conforme al target ✓
    ↓
List<BlissSymbol>  (1..N per token da tier 4)
    ↓
BlissViewModel: resolve ComposedBlissWord? per ogni SEMANTIC symbol (Patch 8)
    ↓
  renderMode == CLASSIC     →  BlissGlyphXBuilder → BlissRenderer.render()
  renderMode == STRUCTURED  →  BlissRenderer.renderWithAttachments()  (Patch 7+9)
    ↓
LinearLayout (one SvgCellView per symbol / component)
```

---

## Gap Analysis — stato aggiornato a Patch 9

### GAP-1 — `MorfologikTagMapper`: ✅ CHIUSO

**Verificato da sorgente**: `MorfologikTagMapper` è già un `object` standalone
(`MorfologikTagMapper.kt`) con `toBlissIndicators(rawTag)` pubblico e testabile.
`LemmaAnalysis` espone già `rawTag: String?` e `blissIndicators: List<String>`.
`MorfologikLemmatizer.analyzeWithTags()` delega a `MorfologikTagMapper`.

**Nessuna modifica richiesta.**

---

### GAP-2 — `compositionStage` naming `[BASSA]`

**Stato**: Non critico. `ComposedBlissWord.compositionStage` usa `Stage.A/B/C`
con KDoc preciso. Rinominare in `CompositionPath` con `SYNONYM_SYNSET /
SEMANTIC_DECOMPOSITION / ORTHOGRAPHIC` è migliorativo ma è un breaking rename
che tocca test e BlissTranslator. Rimandato a patch futura.

---

### GAP-3 — `MatchType.COMPOUND`: ✅ CHIUSO

**Verificato da sorgente**: `MatchType.COMPOUND` è presente in
`BlissSymbol.MatchType` con KDoc preciso ("Stage C, legacy opt-in fallback").
`ComposedBlissWord.toFlatSymbol()` usa `COMPOUND` per Stage C e `SEMANTIC`
per Stage A/B — allineato al target.

**Nessuna modifica richiesta.**

---

### GAP-4 — `attachIndicators()` ✅ CHIUSO (conforme)

Already implemented correctly in `BlissTranslator.attachIndicators()`.

---

### GAP-5 — Naming Tier 3g → Tier 4 `[BASSA]`

Documentazione aggiornata in questo file. I commenti in `BlissTranslator.kt`
allineati a "Tier 4 (Semantic composition)" a Patch 9.

---

### Fragment render dispatch: ✅ CHIUSO — Patch 9

**Implementato in `BlissTranslateFragment.observeViewModel()`**:

```kotlin
val firstComposed = state.composedWords.firstOrNull { it != null }
if (state.renderMode == BlissViewModel.RenderMode.STRUCTURED
    && firstComposed != null) {
    renderer.renderWithAttachments(container, firstComposed)
} else {
    // classic chip/CAA path via applySymbols()
}
```

`BlissRenderer` è ora istanza lazy del Fragment; `cancelRender()` chiamato in
`onDestroyView()` per evitare memory leak.

---

### `BlissViewModelTest` Patch 8 fields: ✅ CHIUSO — Patch 9

Aggiunti in `BlissViewModelTest.kt`:
- `renderModeDefaultIsClassic` — asserta default `CLASSIC`
- `composedWordsDefaultIsEmpty` — asserta default `emptyList()`
- `copyWithStructuredRenderMode` — copy con `STRUCTURED`
- `copyWithComposedWords` — copy con lista non-null
- `statsCountsSemanticBucket` — `TranslationStats.semantic` conta SEMANTIC
- `statsMixedWithSemantic` — bucket indipendenti
- `statsSemanticZeroWhenAbsent` — zero quando nessun SEMANTIC
- `setRenderModeStructured` / `setRenderModeBackToClassic` — FakeViewModel helpers
- `setComposedWordsWithNulls` / `setComposedWordsEmpty` — FakeViewModel helpers

---

## Conformità attuale vs. target

| Componente | Conformità | Gap | Stato |
|---|---|---|---|
| Tier 3a exact surface | 100% | — | ✅ |
| Tier 3b Morfologik lemma+tag | 100% | GAP-1 | ✅ CLOSED |
| Tier 3c–3f fallback | 100% | — | ✅ |
| Tier 4 composer | 100% | GAP-2 naming (opzionale), GAP-3 | ✅ CLOSED |
| `attachIndicators()` | 100% | — | ✅ |
| `MorfologikTagMapper` pubblico | 100% | GAP-1 | ✅ CLOSED |
| Fragment render dispatch | 100% | — | ✅ Patch 9 |
| `BlissViewModelTest` Patch 8 | 100% | — | ✅ Patch 9 |
| Documentazione naming Tier 4 | 100% | GAP-5 | ✅ Patch 9 |

---

## Roadmap post-Patch 9

| Priorità | Task | File coinvolti |
|---|---|---|
| BASSA | GAP-2: Rinominare `compositionStage` → `compositionPath` con enum esplicito | `ComposedBlissWord.kt`, `BlissSemanticComposer.kt` |
| MEDIA | `MorfologikTagMapperTest.kt` — test mapper isolato | test/ |
| MEDIA | Estendere Fragment dispatch per render multi-token (più ComposedBlissWord) | `BlissTranslateFragment.kt` |

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

| MatchType | Source | `TranslationStats` field | Note |
|---|---|---|---|
| EXACT | Surface / FTS4 | `exact` | |
| NGRAM | Multi-word phrase | `ngram` | |
| LEMMA | Morfologik / CSV lemma | `lemma` | |
| SEMANTIC | Stage A / 4a synset | `semantic` | |
| COMPOUND | Stage B/C / 4b bucket | — | presente in enum (GAP-3 ✅ CLOSED) |
| UNKNOWN | Nessun match | `unknown` | |
| FALLBACK_CATEGORY | Categoria broad | — | |

---

## TranslationStats (Patch 8)

```kotlin
data class TranslationStats(
    val total:    Int,   // tutti i simboli
    val exact:    Int,   // EXACT
    val lemma:    Int,   // LEMMA
    val ngram:    Int,   // NGRAM
    val semantic: Int,   // SEMANTIC (Stage A / 4a) — aggiunto Patch 8
    val unknown:  Int    // UNKNOWN
)
// coverage = (total - unknown) / total
```

---

## UiState fields (Patch 8 + 9)

| Field | Type | Description |
|---|---|---|
| `composedWords` | `List<ComposedBlissWord?>` | Per-token strutturato; null = non-semantic |
| `renderMode` | `RenderMode` | `CLASSIC` o `STRUCTURED` |

---

## Fragment render dispatch — ✅ implementato in Patch 9

```kotlin
// BlissTranslateFragment.observeViewModel() — Patch 9
val firstComposed = state.composedWords.firstOrNull { it != null }
if (state.renderMode == BlissViewModel.RenderMode.STRUCTURED
    && firstComposed != null) {
    renderer.renderWithAttachments(
        container as LinearLayout,
        firstComposed
    )
} else {
    applySymbols(state.symbols)  // classic chip/CAA path
}
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
| `BlissViewModelTest.kt` | UiState, Stats, renderMode, composedWords (Patch 9) | ✅ |
| `MorfologikTagMapperTest.kt` | Tag mapper isolato | TODO post-Patch 9 |
