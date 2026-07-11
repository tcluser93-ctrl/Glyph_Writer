# INTEGRATION.md — Glyph Writer · Bliss Engine Integration
**Patch 15** — 2026-07-11

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

## GAP Analysis — stato al Patch 15

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

**16/16 GAP chiusi — integrazione completa ✅**

### Unico debito tecnico residuo

| Task | Stato | Note |
|------|-------|------|
| Test unitari (NLP, BlissTranslator, BlissRenderer) | ⬜ aperto | Non bloccante per il flusso principale. `MorfologikTagMapperTest` (18 test) già presente. Mancano test per `BlissTranslator`, `BlissRenderer`, percorso NLP end-to-end. |

---

## Conformità componenti — Patch 15

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

**13/13 componenti al 100% ✅**

---

## Roadmap — Patch 15

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
| MEDIA | Export PNG/SVG/PDF — fabShare → ExportBottomSheetFragment | ✅ **P15** |
| BASSA | Test unitari BlissTranslator, BlissRenderer, NLP end-to-end | ⬜ aperto |
| BASSA | Test UI BlissTranslateFragment (Espresso) | ⬜ aperto |

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

### Cosa è stato rimosso

- Funzione privata `shareSvg()` (~40 righe): logica ora delegata a `BlissExportHelper`
- 5 import orfani: `android.content.Intent`, `androidx.core.content.FileProvider`,
  `java.io.File`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`
- Costante `FILE_PROVIDER_AUTHORITY` dal `companion object` (già presente in `BlissExportHelper`)

### Comportamento post-P15

| Azione utente | Prima di P15 | Dopo P15 |
|--------------|-------------|----------|
| Tap fabShare (con traduzione attiva) | Share SVG inline | Bottom sheet: SVG / PNG / PDF |
| Tap fabShare (senza traduzione) | NullPointerException potenziale | No-op silenzioso (guard null) |

---

## Audit backlog — task dichiarati aperti vs. stato reale (2026-07-11)

Audit eseguito leggendo il codice sorgente direttamente dal repo.
Tutti i task contrassegnati come “apert“ nel documento di backlog allegato
alla sessione del 2026-07-11 sono stati rivalutati:

| Task (backlog) | Stato dichiarato | Stato reale da codice |
|---|---|---|
| CAA simbolo×simbolo — paginazione Fragment | ❌ aperto | ✅ già completo: `RecyclerView`, `BlissSymbolCardAdapter`, `btnPrev`/`btnNext`, `updateCaaNavButtons()`, persistenza `onSaveInstanceState` |
| Debounce raffinamento (cancel su destroy, indicator loading) | ⚠️ parziale | ✅ già completo: `debounceJob?.cancel()` in `onDestroyView()`, `progressBar.isVisible = state.isLoading`, `btnTranslate.isEnabled = !state.isLoading` |
| Export PNG/SVG — collegare helper alla UI | ⚠️ helper presente, UI assente | ✅ chiuso da P15: `fabShare` apre `ExportBottomSheetFragment` |
| TalkBack / haptic feedback CAA | ❌ da implementare | ✅ già completo: E-03 (`bliss_caa_svg_loading/ready/missing`, `setTraversalAfter`, `bliss_caa_card_cd`), E-06 (`hapticTick()` con API 26+/31+, `AppPreferences.isHapticCaa`) |
| Test unitari (NLP, Translator, Renderer) | ❌ da implementare | ⬜ aperto confermato: solo `MorfologikTagMapperTest` (18 test) presente |

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

### Perché `visibility="gone"` di default

Il Fragment gestisce la visibilità tramite `applyCaaVisibility()`. Con CAA attivo,
sia `symbol_container` che `mixed_bliss_row_view` vengono nascosti. Con CAA
disattivo e traduzione multi-token, solo `mixed_bliss_row_view` viene mostrato.
La view parte da `GONE` per evitare layout shift al primo render.

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

---

## Nota finale — stato build

Dopo Patch 15, il flusso principale è completo al 100%:
- Traduzione NLP multi-lingua con lemmatizzazione Morfologik
- Rendering SVG asincrono (chip classico + modalità CAA card + MixedBlissRowView)
- Export SVG / PNG / PDF via bottom sheet con FileProvider
- TalkBack completo su chip, card CAA e pulsanti di navigazione
- Haptic feedback configurabile su pulsanti CAA
- Persistenza stato CAA su rotation e background kill

Debito tecnico residuo: test unitari per `BlissTranslator`, `BlissRenderer`,
e il percorso NLP end-to-end (non bloccante per il release).
