# BlissTranslator — Integration Guide

> Last updated: **Patch 14 — fix cast FrameLayout in renderMixedRow** (2026-07-10)

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
  renderMode == STRUCTURED (1 token) →  BlissRenderer.renderWithAttachments()  ✅ P9
  renderMode == STRUCTURED (multi)   →  MixedBlissRowView.bind() + svgContainerFor() ✅ P11/P14
    ↓
FlexboxLayout (classic) / MixedBlissRowView (multi structured) / CAA RecyclerView
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
| Fragment dispatch multi-token | renderMixedRow() via MixedBlissRowView | ✅ CLOSED (P11) |
| BlissViewModelTest Patch 8 fields | renderMode, composedWords, semantic | ✅ CLOSED (P9) |
| MorfologikTagMapperTest | test isolato mapper | ✅ CLOSED (post-P9) |
| MixedBlissRowView | container unificato chip+SVG multi-token | ✅ CLOSED (P10) |
| Fragment integrazione MixedBlissRowView | renderMixedRow sostituisce renderStructuredMultiToken | ✅ CLOSED (P11) |
| MixedBlissRowView nel layout XML | fragment_translate.xml mixed_bliss_row_view | ✅ CLOSED (P12) |
| cast FrameLayout in renderMixedRow | svgContainerFor() as? FrameLayout (era LinearLayout) | ✅ CLOSED (P14) |

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

## Patch 14 — fix cast FrameLayout in renderMixedRow

`MixedBlissRowView.svgContainerFor()` restituisce `FrameLayout?`, non `LinearLayout?`.
Il cast `as? LinearLayout` in `renderMixedRow()` era silenziosamente `null` per ogni
`SvgSlot`, rendendo il rendering SVG multi-token completamente non funzionante a runtime
senza produrre crash visibili (il `?: return@forEach` mascherava il problema).

**Fix applicato in `BlissTranslateFragment.kt`:**

```kotlin
// Prima (errato — sempre null a runtime)
val container = mixedRowView.svgContainerFor(svgSlot.composedWord.sourceWord)
    as? LinearLayout ?: return@forEach

// Dopo (corretto — FrameLayout è il tipo effettivo restituito)
val container = mixedRowView.svgContainerFor(svgSlot.composedWord.sourceWord)
    ?: return@forEach
```

`BlissRenderer.renderWithAttachments()` accetta `ViewGroup` come primo parametro,
super-classe sia di `FrameLayout` che di `LinearLayout` — nessuna modifica al renderer.

---

## Patch 13 — fix alias, campi e componenti

Risolti 15 errori di compilazione in 3 file:

| File | Errori | Fix |
|---|---|---|
| `BlissSemanticComposer.kt` | 3 | `compositionStage=Stage.*` → `compositionPath=CompositionPath.*` |
| `MixedBlissRowView.kt` | 5 | `sourceToken`→`sourceWord`, `it.symbol.label`→`component.symbol.name` |
| `BlissTranslateFragment.kt` | 1 (riga 566) | `sourceToken`→`sourceWord` |

---

## Patch 12 — MixedBlissRowView nel layout XML

`fragment_translate.xml`: aggiunto blocco dopo `@id/symbol_container`:
```xml
<com.blueapps.egyptianwriter.bliss.MixedBlissRowView
    android:id="@+id/mixed_bliss_row_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="16dp"
    android:visibility="gone"
    tools:visibility="visible" />
```
`bindViews()` può ora trovare la view senza `NullPointerException`.

---

## Patch 11 — Fragment MixedBlissRowView integration

`renderStructuredMultiToken()` è stato rimosso da `BlissTranslateFragment`.
Il branch multi-token STRUCTURED ora chiama `renderMixedRow(state)` che:

1. Costruisce una `List<MixedTokenSlot>` da `UiState.symbols` + `UiState.composedWords`
2. Chiama `mixedRowView.bind(slots)` — ordine visivo garantito per indice
3. Per ogni `SvgSlot`, lancia una coroutine che chiama
   `BlissRenderer.renderWithAttachments(mixedRowView.svgContainerFor(token), composedWord)`

---

## Patch 10 — MixedBlissRowView

`MixedBlissRowView` è un `LinearLayout` orizzontale custom che sostituisce il pattern
`applySymbols() + renderStructuredMultiToken()` per gli output multi-token `STRUCTURED`.

### MixedTokenSlot — gerarchia sealed

| Variante | Contenuto | Rendering |
|---|---|---|
| `ChipSlot(index, symbol)` | Token classic | Chip BCI-flat |
| `SvgSlot(index, composedWord)` | Token structured risolto | FrameLayout container → `BlissRenderer` |
| `PendingSlot(index)` | Placeholder asincrono | Space 32×32dp |

### API pubblica

```kotlin
// Imposta tutti gli slot in un'unica chiamata (ordine garantito per index)
mixedRowView.bind(slots: List<MixedTokenSlot>)

// Aggiorna un PendingSlot → SvgSlot quando la coroutine risolve
mixedRowView.resolveSlot(index: Int, composedWord: ComposedBlissWord)

// Recupera il FrameLayout container per il renderer SVG
mixedRowView.svgContainerFor(sourceWord: String): FrameLayout?
```

### Problemi risolti

- **Race condition ordine token**: `bind()` pre-alloca tutti gli slot per indice.
- **Spacing incoerente chip/SVG**: gap uniforme 6dp gestito dalla view.
- **TalkBack traversal spezzato**: `AccessibilityDelegateCompat` centralizzato espone una singola `contentDescription` lineare.

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
| Fragment render dispatch multi | 100% | ✅ P11 |
| `BlissViewModelTest` Patch 8 | 100% | ✅ P9 |
| `CompositionPath` rename (GAP-2) | 100% | ✅ post-P9 |
| `MixedBlissRowView` container | 100% | ✅ P10 |
| Fragment integra MixedBlissRowView | 100% | ✅ P11 |
| `MixedBlissRowView` nel layout XML | 100% | ✅ P12 |
| Fix alias/campi (sourceToken, label) | 100% | ✅ P13 |
| Cast FrameLayout in renderMixedRow | 100% | ✅ P14 |

---

## Roadmap post-Patch 14

| Priorità | Task | Note |
|---|---|---|
| BASSA | Rimuovere `typealias Stage` e `@Deprecated compositionStage` | Dopo migrazione completa di tutti i call-site a `CompositionPath` |
| BASSA | `ComposedBlissWord.Stage.A/B/C` → `CompositionPath.*` in `BlissSemanticComposerTest` | Prerequisito per la rimozione del typealias |

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
