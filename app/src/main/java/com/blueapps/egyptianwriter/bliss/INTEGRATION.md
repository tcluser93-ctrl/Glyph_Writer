# BlissTranslator — Integration Guide

> Last updated: **Patch 8 + Gap Analysis** (2026-07-09)

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
  │        [ GAP-1: rawPosTags non ancora esposto — Patch 9 ]
  Tier 3c  lookupLemma(word)                   → LEMMA
  Tier 3d  heuristicPos() + lookupLemmaPos()   → LEMMA
  Tier 3e  simpleDeaffix() + lookup            → LEMMA
  Tier 3f  lookupSurfaceDb() Room FTS4         → EXACT
  Tier 3g / 4  BlissSemanticComposer
  │        Solo se tutti i tier precedenti → UNKNOWN
  │        4a (Stage A) synset diretto        → SEMANTIC
  │        4b (Stage B) scomposizione bucket  → SEMANTIC  [ GAP-3: should be COMPOUND ]
  │        4c (Stage C) ortografico (off)     → SEMANTIC
  │        [ GAP-2: compositionStage non mappa 4a/4b esplicitamente ]
  Tier 3h  UNKNOWN
    ↓
attachIndicators()    ← già conforme al target (✓)
    ↓
List<BlissSymbol>  (1..N per token da tier 3g)
    ↓
BlissViewModel: resolve ComposedBlissWord? per ogni SEMANTIC symbol (Patch 8)
    ↓
  renderMode == CLASSIC     →  BlissGlyphXBuilder → BlissRenderer.render()
  renderMode == STRUCTURED  →  BlissRenderer.renderWithAttachments()  (Patch 7)
    ↓
LinearLayout (one SvgCellView per symbol / component)
```

---

## Gap Analysis — target architetturale vs. implementato

La struttura target proposta introduce quattro differenze rispetto allo stato `main`.

### GAP-1 — `MorfologikTagMapper`: classe pubblica mancante `[ALTA]`

**Target**: `MorfologikTagMapper.toBlissModifiers(rawTag, lang)` è una classe
standalone testabile separatamente da `MorfologikLemmatizer`.

**Attuale**: il mapping da raw FSA tag a `blissIndicators` è inlinato dentro
`MorfologikLemmatizer.analyzeWithTags()`.  Il raw tag non è esposto in
`LemmaAnalysis`, che attualmente restituisce solo `lemma` e `blissIndicators`.

**Conseguenze**:
- Impossibile aggiungere nuovi mapping di tag (es. `Noun+Pl`, `ADJ:comp`)
  senza modificare `MorfologikLemmatizer`.
- Non testabile in isolamento.

**Soluzione Patch 9**:
```kotlin
// Nuovo file: MorfologikTagMapper.kt
object MorfologikTagMapper {
    fun toBlissModifiers(rawTag: String, lang: String): List<String> = buildList {
        if (rawTag.contains("past", ignoreCase = true) || rawTag.contains(":past")) add("past")
        if (rawTag.contains("fut",  ignoreCase = true) || rawTag.contains(":fut"))  add("future")
        if (rawTag.contains("pl",   ignoreCase = true) || rawTag.contains("Pl"))    add("plural")
    }
}

// LemmaAnalysis aggiornato:
data class LemmaAnalysis(
    val lemma:           String,
    val rawPosTags:      List<String>,  // <─ NUOVO Patch 9
    val blissIndicators: List<String>   // derivato da MorfologikTagMapper
)
```

---

### GAP-2 — `compositionStage` non mappa esplicitamente 4a/4b `[MEDIA]`

**Target**: Stage 4a = sinonimo diretto (synset), Stage 4b = scomposizione
bucket/NLP, distinguibili nel risultato.

**Attuale**: `ComposedBlissWord.compositionStage` usa enum `A`, `B`, `C`
che corrispondono a 4a/4b/4c ma non sono nominati come tali nel codice.

**Soluzione Patch 9**:
```kotlin
// ComposedBlissWord.kt
enum class CompositionPath {
    SYNONYM_SYNSET,        // target: 4a — synset diretto (ex Stage A)
    SEMANTIC_DECOMPOSITION,// target: 4b — bucket/NLP composition (ex Stage B)
    ORTHOGRAPHIC           // target: 4c — ortografico (ex Stage C, off by default)
}

data class ComposedBlissWord(
    val components:      List<ResolvedBlissComponent>,
    val compositionPath: CompositionPath,  // <─ RINOMINATO da compositionStage
    val sourceWord:      String
)
```

---

### GAP-3 — `MatchType.COMPOUND` rimosso in Patch 7 `[ALTA]`

**Target**: Stage 4b (scomposizione semantica NLP) produce `MatchType.COMPOUND`
per simboli costruiti come `[modificatore] + [radice]`.
Esempio: `"calciatore"` → BCI 14962 (`calcio`) + BCI 13943 (PERSON)
→ `MatchType.COMPOUND`.

**Attuale**: Patch 7 ha eliminato `MatchType.COMPOUND` e usa `MatchType.SEMANTIC`
per *tutti* i risultati di `composeStructured()`, sia Stage A che B.
Quindi un sinonimo diretto (4a) e una composizione (4b) hanno lo stesso `MatchType`,
rendendo la distinzione opaca all'UI e alle statistiche.

**Soluzione Patch 9**:
```kotlin
// BlissSymbol.kt
enum class MatchType {
    EXACT, NGRAM, LEMMA,
    SEMANTIC,   // Stage A / 4a: sinonimo diretto da synset
    COMPOUND,   // Stage B / 4b: composizione [modif.]+[radice]  <─ RIPRISTINATO
    UNKNOWN,
    FALLBACK_CATEGORY
}

// BlissTranslator.kt — tier 3g
composed.components.map { component ->
    BlissSymbol(
        bciAvId   = component.bciSymbolId,
        matchType = when (composed.compositionPath) {
            CompositionPath.SYNONYM_SYNSET         -> MatchType.SEMANTIC
            CompositionPath.SEMANTIC_DECOMPOSITION -> MatchType.COMPOUND  // <─ PATCH 9
            CompositionPath.ORTHOGRAPHIC           -> MatchType.SEMANTIC
        },
        ...
    )
}
```

`TranslationStats` aggiunge il contatore `compound: Int`.

---

### GAP-4 — `attachIndicators()` ✔ già conforme

**Target**: `attachIndicators()` è un passo separato dopo tutti i tier,
che salta simboli già con indicatori per-token.

**Attuale**: già implementato correttamente in `BlissTranslator.attachIndicators()`.
Nessuna modifica richiesta.

---

### GAP-5 — Naming "Tier 3g" vs. "Tier 4" `[BASSA]`

**Target**: il composer è "Tier 4" con sub-tier 4a/4b.

**Attuale**: è "Tier 3g" in tutti i commenti KDoc e in questa documentazione.

**Soluzione Patch 9**: allineare naming in `BlissTranslator.kt` (commenti)
e in questo file. Impatto zero sul comportamento runtime.

---

## Conformità attuale vs. target

| Componente | Conformità | Gap | Patch |
|---|---|---|---|
| Tier 3a exact surface | 100% | — | ✔ |
| Tier 3b Morfologik lemma+tag | 85% | GAP-1 rawPosTags | 9 |
| Tier 3c–3f fallback | 100% | — | ✔ |
| Tier 3g/4 composer | 70% | GAP-2 path naming, GAP-3 COMPOUND | 9 |
| `attachIndicators()` | 100% | — | ✔ |
| `MorfologikTagMapper` pubblico | 40% | GAP-1 non estratto | 9 |
| Fragment render dispatch | 0% | TODO da Patch 8 | 9 |
| `BlissViewModelTest` Patch 8 | 0% | TODO da Patch 8 | 9 |
| Documentazione naming | 90% | GAP-5 tier naming | 9 |

---

## Roadmap Patch 9

| Priorità | Task | File coinvolti |
|---|---|---|
| ALTA | GAP-1: Estrarre `MorfologikTagMapper`, esporre `rawPosTags` in `LemmaAnalysis` | `MorfologikLemmatizer.kt`, nuovo `MorfologikTagMapper.kt` |
| ALTA | GAP-3: Ripristinare `MatchType.COMPOUND` per Stage 4b | `BlissSymbol.kt`, `BlissSemanticComposer.kt`, `BlissTranslator.kt`, `TranslationStats` |
| ALTA | Fragment render dispatch (TODO Patch 8) | `BlissFragment.kt` |
| MEDIA | GAP-2: Rinominare `compositionStage` → `compositionPath` con enum esplicito | `ComposedBlissWord.kt`, `BlissSemanticComposer.kt` |
| MEDIA | `BlissViewModelTest` update per Patch 8 | test/ |
| BASSA | GAP-5: Allineare naming Tier 3g → Tier 4 in KDoc + docs | `BlissTranslator.kt`, `INTEGRATION.md` |

---

## BlissRenderer — structured path (Patch 7)

`BlissRenderer.renderWithAttachments(container, composedBlissWord)`:
1. Fetches base glyph SVGs per ogni `ResolvedBlissComponent.bciSymbolId`
2. Fetches overlay SVGs per ogni `BlissRenderAttachment` con `isOverlay == true`
3. Disegna overlays in `SvgCellView.drawOverlays()` a offset scalati per densità
4. Aggiunge modificatori lineari (`isOverlay == false`) come celle adiacenti

Overlay size = `OVERLAY_SIZE_RATIO (0.35)` × cell width.
Modifier size = `MODIFIER_SIZE_RATIO (0.55)` × cell width.
Default Y offset = `BlissRenderAttachment.DEFAULT_OVERLAY_Y_OFFSET_PX = -14f`.

---

## MatchType reference

| MatchType | Source | `TranslationStats` field | Target Patch 9 |
|---|---|---|---|
| EXACT | Surface / FTS4 | `exact` | invariato |
| NGRAM | Multi-word phrase | `ngram` | invariato |
| LEMMA | Morfologik / CSV lemma | `lemma` | invariato |
| SEMANTIC | Stage A / 4a synset | `semantic` | solo Stage A |
| COMPOUND | Stage B / 4b bucket | (rimosso P7) | **RIPRISTINARE** (GAP-3) |
| UNKNOWN | Nessun match | `unknown` | invariato |
| FALLBACK_CATEGORY | Categoria broad | — | invariato |

---

## TranslationStats (Patch 8)

```kotlin
data class TranslationStats(
    val total:    Int,   // tutti i simboli
    val exact:    Int,   // EXACT
    val lemma:    Int,   // LEMMA
    val ngram:    Int,   // NGRAM
    val semantic: Int,   // SEMANTIC (Stage A / 4a)
    // val compound: Int  <─ DA AGGIUNGERE Patch 9 (GAP-3)
    val unknown:  Int    // UNKNOWN
)
// coverage = (total - unknown) / total
```

---

## UiState new fields (Patch 8)

| Field | Type | Description |
|---|---|---|
| `composedWords` | `List<ComposedBlissWord?>` | Per-token strutturato; null = non-semantic |
| `renderMode` | `RenderMode` | `CLASSIC` o `STRUCTURED` |

---

## Fragment render dispatch (TODO — Patch 9)

```kotlin
// BlissFragment.kt — da implementare
when (uiState.renderMode) {
    RenderMode.STRUCTURED -> {
        val composed = uiState.composedWords.firstOrNull { it != null }!!
        renderer.renderWithAttachments(container, composed)
    }
    RenderMode.CLASSIC -> renderer.render(container, uiState.glyphXDoc!!)
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
| `BlissSemanticComposerTest.kt` | Stage A/B/C; shim; overlays | ✔ |
| `BlissLookupTest.kt` | CSV loading, lemma/surface/pos | ✔ |
| `BlissGlyphXBuilderCoreTest.kt` | GlyphX Document | ✔ |
| `BlissGlyphXBuilderSvgTest.kt` | SVG export | ✔ |
| `BlissViewModelTest.kt` | Pipeline async | TODO Patch 9 — aggiornare per Patch 8 |
| `MorfologikTagMapperTest.kt` | Tag mapper isolato | TODO Patch 9 (GAP-1) |
