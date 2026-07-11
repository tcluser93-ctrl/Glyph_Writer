# INTEGRATION.md — Glyph Writer · Bliss Engine Integration
**Patch 16** — 2026-07-11

---

## Architettura a componenti

```
BlissTranslateFragment
  │
  ├─ BlissTranslatorEngine          ← parsing, lookup, tokenizzazione
  │     └─ BlissSymbolRepository    ← sorgente dati (Room / Asset)
  │
  ├─ BlissTranslateViewModel        ← StateFlow<UiState>
  │
  ├─ symbol_container (FlexboxLayout)
  │     └─ BlissSymbolChipView      ← chip singolo token (testo o immagine)
  │
  ├─ mixed_bliss_row_view (MixedBlissRowView)   ✅ P12 — presente nel layout XML
  │     └─ MixedBlissRowView.bind(slots)        ← lista MixedTokenSlot
  │           ├─ TextSlot  → chip testuale
  │           └─ SvgSlot   → svgContainerFor(token) + BlissRenderer.renderWithAttachments()
  │
  ├─ caa_container
  │     └─ rv_caa_cards (RecyclerView)
  │           └─ BlissSymbolCardAdapter           ✅ P15 — TalkBack E-03 già completo
  │                 ├─ item_symbol_card.xml
  │                 └─ BlissSignProvider.getDrawableAsync
  │
  └─ ExportBottomSheetFragment        ✅ P15 — aperta da fabShare
        └─ BlissExportHelper            ← SVG / PNG / PDF via FileProvider
```

---

## GAP Analysis — stato al Patch 16

| GAP | Stato |
|-----|-------|
| BlissSymbolRepository | ✅ CLOSED (P1) |
| BlissTranslatorEngine tokenizzazione | ✅ CLOSED (P2) |
| BlissTranslateViewModel StateFlow | ✅ CLOSED (P3) |
| BlissSymbolChipView rendering | ✅ CLOSED (P4) |
| BlissCardAdapter CAA | ✅ CLOSED (P5) |
| item_symbol_card.xml | ✅ CLOSED (P6) |
| BlissRenderer + renderWithAttachments | ✅ CLOSED (P7) |
| MixedBlissRowView + MixedTokenSlot | ✅ CLOSED (P8) |
| svgContainerFor() nel Fragment | ✅ CLOSED (P9) |
| applyCaaVisibility() + scheduleDebounce | ✅ CLOSED (P10) |
| Fragment integrazione MixedBlissRowView (Kotlin) | ✅ CLOSED (P11) |
| MixedBlissRowView nel layout XML | ✅ CLOSED (P12) |
| CAA paginazione Fragment (Blocco C) | ✅ CLOSED — già presente prima di P15 |
| Debounce raffinamento (cancel su destroy, indicator loading) | ✅ CLOSED — già presente prima di P15 |
| TalkBack / haptic feedback CAA (E-03 + E-06) | ✅ CLOSED — già presente prima di P15 |
| Export PNG/SVG/PDF — collegare BlissExportHelper alla UI | ✅ CLOSED (P15) |
| Test unitari BlissTranslator, BlissRenderer, NLP end-to-end | ✅ CLOSED (P16) |

**17/17 GAP chiusi — progetto completo al 100% ✅**

---

## Conformità componenti — Patch 16

| Componente | File | Conformità |
|------------|------|------------|
| BlissSymbolRepository | `BlissSymbolRepository.kt` | 100% |
| BlissTranslatorEngine | `BlissTranslatorEngine.kt` | 100% |
| BlissTranslateViewModel | `BlissTranslateViewModel.kt` | 100% |
| BlissSymbolChipView | `BlissSymbolChipView.kt` | 100% |
| BlissSymbolCardAdapter | `BlissSymbolCardAdapter.kt` | 100% (E-01, E-03 completi) |
| item_symbol_card.xml | `item_symbol_card.xml` | 100% |
| BlissRenderer | `BlissRenderer.kt` | 100% |
| MixedBlissRowView | `MixedBlissRowView.kt` | 100% |
| MixedTokenSlot | `MixedTokenSlot.kt` | 100% |
| BlissExportHelper | `BlissExportHelper.kt` | 100% |
| ExportBottomSheetFragment | `ExportBottomSheetFragment.kt` | 100% |
| BlissTranslateFragment | `BlissTranslateFragment.kt` | 100% (P15: fabShare → ExportBottomSheetFragment) |
| fragment_translate.xml | `fragment_translate.xml` | 100% |
| BlissTranslatorTest | `BlissTranslatorTest.kt` | 100% — 15 test JVM (P16) |
| BlissRendererTest | `BlissRendererTest.kt` | 100% — 15 test JVM (P16) |

**15/15 componenti al 100% ✅**

---

## Roadmap — Patch 16

| Priorità | Task | Stato |
|----------|------|-------|
| CRITICA | BlissTranslatorEngine tokenizzazione multi-token | ✅ P2 |
| CRITICA | ViewModel StateFlow UiState | ✅ P3 |
| CRITICA | BlissRenderer renderWithAttachments | ✅ P7 |
| CRITICA | MixedBlissRowView Kotlin | ✅ P8 |
| CRITICA | svgContainerFor() Fragment | ✅ P9 |
| CRITICA | applyCaaVisibility / scheduleDebounce | ✅ P10 |
| CRITICA | Fragment integrazione renderMixedRow | ✅ P11 |
| MEDIA | MixedBlissRowView nel layout XML | ✅ P12 |
| MEDIA | CAA paginazione Fragment + nav buttons | ✅ già implementato (audit P15) |
| MEDIA | Debounce: cancel onDestroy + loading indicator | ✅ già implementato (audit P15) |
| MEDIA | TalkBack card CAA (E-03) + haptic (E-06) | ✅ già implementato (audit P15) |
| MEDIA | Export PNG/SVG/PDF — fabShare → ExportBottomSheetFragment | ✅ P15 |
| BASSA | Test unitari BlissTranslator, BlissRenderer, NLP end-to-end | ✅ **P16** |
| BASSA | Test UI BlissTranslateFragment (Espresso) | ⬜ fuori scope (non bloccante) |

---

## Dettaglio Patch 16 — test unitari JVM (30 test)

### Contesto

L'unico debito tecnico residuo documentato in Patch 15 erano i test unitari per
`BlissTranslator`, `BlissRenderer`, e il percorso NLP end-to-end.
`MorfologikTagMapperTest` (18 test) era già presente.

### File aggiunti

| File | Percorso | Test |
|------|----------|------|
| `BlissTranslatorTest.kt` | `app/src/test/java/com/blueapps/egyptianwriter/bliss/` | 15 (T-01 → T-15) |
| `BlissRendererTest.kt` | `app/src/test/java/com/blueapps/egyptianwriter/bliss/` | 15 (R-01 → R-15) |

### Copertura BlissTranslatorTest (T-01 → T-15)

| ID | Scenario |
|----|----------|
| T-01 | Input vuoto → lista vuota |
| T-02 | `lookup.isReady == false` → lista vuota, no crash |
| T-03 | Token singolo EXACT via `lookupSurface` |
| T-04 | Token singolo LEMMA via `lookupLemma` |
| T-05 | De-affixazione suffix `-ing` → candidato senza suffisso |
| T-06 | Token sconosciuto → `UNKNOWN` con `bciAvId == UNKNOWN_SYMBOL_ID` |
| T-07 | `detectIndicators`: keyword `"many"` → `INDICATOR_PLURAL` |
| T-08 | `detectIndicators`: `"will"` → `INDICATOR_FUTURE` |
| T-09 | `detectIndicators`: `"had … walked"` → `INDICATOR_PAST` |
| T-10 | `attachIndicators`: non attacca a simboli UNKNOWN |
| T-11 | `attachIndicators`: non ri-attacca se `indicators` già non vuoto |
| T-12 | `attachIndicators`: attacca correttamente a EXACT con indicators vuoti |
| T-13 | Normalise: punteggiatura rimossa, lowercase, trim |
| T-14 | `translateAsync` tier 3b: Morfologik mock risolve forma flessa (`runTest`) |
| T-15 | N-gram bi-token risolto via `lookupNgram` |

### Copertura BlissRendererTest (R-01 → R-15)

| ID | Scenario |
|----|----------|
| R-01 | `BlissRenderAttachment.isOverlay == true` per BCI overlay |
| R-02 | `BlissRenderAttachment.isOverlay == false` per attachment non-overlay |
| R-03 | `withIndicators` restituisce copia con nuovi indicatori; originale immutato |
| R-04 | `isUnknown` true solo per `UNKNOWN` |
| R-05 | `isCompound` true solo per `COMPOUND` |
| R-06 | `isSemanticComposition` true solo per `SEMANTIC` |
| R-07 | `gloss(maxLen)` tronca con ellissi Unicode |
| R-08 | `gloss(maxLen >= name.length)` restituisce nome intero |
| R-09 | `init` lancia `IllegalArgumentException` per `bciAvId == 0` su EXACT |
| R-10 | `init` lancia `IllegalArgumentException` per `name` blank |
| R-11 | UNKNOWN accetta `UNKNOWN_SYMBOL_ID` sentinel |
| R-12 | COMPOUND accetta `COMPOUND_SYMBOL_ID` sentinel |
| R-13 | SEMANTIC con `COMPOUND_SYMBOL_ID` accettato come sentinel |
| R-14 | `componentIds` popolati correttamente per COMPOUND |
| R-15 | `indicators` vuoti di default; `withIndicators` non muta originale |

### Strategia

- `BlissTranslatorTest`: `BlissLookup` e `MorfologikLemmatizer` stubbed con
  **Mockito-Kotlin 5.4.0** — zero dipendenze da Room/assets/Android SDK.
- `BlissRendererTest`: **zero mock** — testa la logica pura di `BlissSymbol`
  e `BlissRenderAttachment`, compilabile su JVM pura.
- `translateAsync` (T-14) usa `kotlinx-coroutines-test 1.9.0` (`runTest`).
- Nessun test richiede Robolectric o device fisico.
- Dipendenze già presenti in `gradle/libs.versions.toml` e `app/build.gradle.kts`
  prima di P16: nessuna modifica al build necessaria.

### Suite test completa dopo P16

| File | Test | Tipo |
|------|------|------|
| `MorfologikTagMapperTest.kt` | 18 | JVM puro |
| `BlissTranslatorTest.kt` | 15 | JVM + Mockito |
| `BlissRendererTest.kt` | 15 | JVM puro |
| **Totale** | **48** | |

Esecuzione: `./gradlew :app:test --tests "com.blueapps.egyptianwriter.bliss.*"`

---

## Nota finale — stato progetto dopo Patch 16

Tutti i gap funzionali e il debito tecnico documentato sono chiusi:

- ✅ Traduzione NLP multi-lingua con lemmatizzazione Morfologik
- ✅ Rendering SVG asincrono (chip classico + CAA card + MixedBlissRowView)
- ✅ Export SVG / PNG / PDF via bottom sheet con FileProvider
- ✅ TalkBack completo su chip, card CAA e pulsanti di navigazione
- ✅ Haptic feedback configurabile su pulsanti CAA
- ✅ Persistenza stato CAA su rotation e background kill
- ✅ 48 test unitari JVM (MorfologikTagMapper + BlissTranslator + BlissRenderer)

Nessun debito tecnico bloccante residuo.
Test UI Espresso (`BlissTranslateFragment`) rimangono fuori scope per scelta esplicita.

---

## Dettaglio Patch 15 — collegamento fabShare → ExportBottomSheetFragment

### Problema

`ExportBottomSheetFragment` (SVG, PNG, PDF via `BlissExportHelper`) era già completa
ma non veniva aperta da nessuna parte nella UI: `BlissTranslateFragment.setupFabShare()`
chiamava la funzione inline `shareSvg()` che replicava solo l'export SVG.

### Soluzione applicata

**File modificato:** `BlissTranslateFragment.kt`

```kotlin
// PRIMA (Patch 14 e precedenti)
private fun setupFabShare() {
    fabShare.setOnClickListener { shareSvg() }   // solo SVG, logica duplicata
}

// DOPO (Patch 15)
private fun setupFabShare() {
    fabShare.setOnClickListener {
        val builder = glyphXBuilder ?: return@setOnClickListener   // guard null
        val doc     = vm.uiState.value.glyphXDoc ?: return@setOnClickListener
        ExportBottomSheetFragment
            .newInstance(builder, doc)
            .show(childFragmentManager, ExportBottomSheetFragment.TAG)
    }
}
```

### Comportamento post-P15

| Azione utente | Prima di P15 | Dopo P15 |
|--------------|-------------|----------|
| Tap fabShare (con traduzione attiva) | Share SVG inline | Bottom sheet: SVG / PNG / PDF |
| Tap fabShare (senza traduzione) | NullPointerException potenziale | No-op silenzioso (guard null) |

---

## Audit backlog — task dichiarati aperti vs. stato reale (2026-07-11)

| Task (backlog) | Stato dichiarato | Stato reale da codice |
|---|---|---|
| CAA simbolo×simbolo — paginazione Fragment | ❌ aperto | ✅ già completo: `RecyclerView`, `BlissSymbolCardAdapter`, `btnPrev`/`btnNext`, `updateCaaNavButtons()`, persistenza `onSaveInstanceState` |
| Debounce raffinamento (cancel su destroy, indicator loading) | ⚠️ parziale | ✅ già completo: `debounceJob?.cancel()` in `onDestroyView()`, `progressBar.isVisible = state.isLoading`, `btnTranslate.isEnabled = !state.isLoading` |
| Export PNG/SVG — collegare helper alla UI | ⚠️ helper presente, UI assente | ✅ chiuso da P15: `fabShare` apre `ExportBottomSheetFragment` |
| TalkBack / haptic feedback CAA | ❌ da implementare | ✅ già completo: E-03 (`bliss_caa_svg_loading/ready/missing`, `setTraversalAfter`, `bliss_caa_card_cd`), E-06 (`hapticTick()` con API 26+/31+, `AppPreferences.isHapticCaa`) |
| Test unitari (NLP, Translator, Renderer) | ❌ da implementare | ✅ chiuso da P16: 30 nuovi test JVM su `main` (commit 54739e0) |

---

## Dettaglio Patch 12 — fragment_translate.xml

### Modifica applicata

Aggiunto `MixedBlissRowView` con `android:id="@+id/mixed_bliss_row_view"` subito dopo `symbol_container` (FlexboxLayout), all'interno del `LinearLayout` verticale principale nel `NestedScrollView`.

```xml
<!-- PATCH 12 — Riga mista testo+SVG in modalità standard multi-token -->
<com.blueapps.egyptianwriter.bliss.MixedBlissRowView
    android:id="@+id/mixed_bliss_row_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="16dp"
    android:visibility="gone"
    tools:visibility="visible" />
```

### View IDs nel Fragment dopo Patch 15

```
spinner_language, input_layout_text, edit_input_text,
label_suggestions, rv_suggestions, btn_translate,
progress_translate, text_output_label, text_output,
label_symbols, symbol_container,
mixed_bliss_row_view,
fab_share, switch_caa_mode,
caa_container, rv_caa_cards, btn_prev, btn_next
```
