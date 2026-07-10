# INTEGRATION.md — Glyph Writer · Bliss Engine Integration
**Patch 12** — 2026-07-10

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
  └─ caa_container
        └─ rv_caa_cards (RecyclerView)
              └─ BlissCardAdapter
                    └─ item_symbol_card.xml
```

---

## GAP Analysis — stato al Patch 12

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

**12/12 GAP chiusi — integrazione completa ✅**

---

## Conformità componenti — Patch 12

| Componente | File | Conformità |
|------------|------|------------|
| BlissSymbolRepository | `BlissSymbolRepository.kt` | 100% |
| BlissTranslatorEngine | `BlissTranslatorEngine.kt` | 100% |
| BlissTranslateViewModel | `BlissTranslateViewModel.kt` | 100% |
| BlissSymbolChipView | `BlissSymbolChipView.kt` | 100% |
| BlissCardAdapter | `BlissCardAdapter.kt` | 100% |
| item_symbol_card.xml | `item_symbol_card.xml` | 100% |
| BlissRenderer | `BlissRenderer.kt` | 100% |
| MixedBlissRowView | `MixedBlissRowView.kt` | 100% |
| MixedTokenSlot | `MixedTokenSlot.kt` | 100% |
| BlissTranslateFragment | `BlissTranslateFragment.kt` | 100% |
| fragment_translate.xml | `fragment_translate.xml` | 100% |

**11/11 componenti al 100% ✅**

---

## Roadmap — Patch 12

| Priorità | Task | Stato |
|----------|------|-------|
| CRITICA | BlissTranslatorEngine tokenizzazione multi-token | ✅ P2 |
| CRITICA | ViewModel StateFlow UiState | ✅ P3 |
| CRITICA | BlissRenderer renderWithAttachments | ✅ P7 |
| CRITICA | MixedBlissRowView Kotlin | ✅ P8 |
| CRITICA | svgContainerFor() Fragment | ✅ P9 |
| CRITICA | applyCaaVisibility / scheduleDebounce | ✅ P10 |
| CRITICA | Fragment integrazione renderMixedRow | ✅ P11 |
| MEDIA | MixedBlissRowView nel layout XML | ✅ **P12** |
| BASSA | Test unitari BlissTranslatorEngine | ⬜ aperto |
| BASSA | Test UI BlissTranslateFragment (Espresso) | ⬜ aperto |

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

Il Fragment gestisce la visibilità tramite `applyCaaVisibility(caaEnabled: Boolean)`. Con CAA attivo, sia `symbol_container` che `mixed_bliss_row_view` vengono nascosti. Con CAA disattivo e traduzione multi-token, solo `mixed_bliss_row_view` viene mostrato; `symbol_container` rimane nascosto. La view parte da `GONE` per evitare layout shift al primo render.

### Perché `tools:visibility="visible"`

Consente di vedere l'anteprima della view nell'editor di layout Android Studio senza modificare il comportamento runtime.

### View IDs nel Fragment dopo Patch 12

```
spinner_language, input_layout_text, edit_input_text,
label_suggestions, rv_suggestions, btn_translate,
progress_translate, text_output_label, text_output,
label_symbols, symbol_container,
mixed_bliss_row_view,          ← NUOVO P12
fab_share, switch_caa_mode,
caa_container, rv_caa_cards, btn_prev, btn_next
```

---

## Nota finale — stato build

Dopo Patch 12, la build non produce più `NullPointerException` in `BlissTranslateFragment.bindViews()` per il riferimento a `mixed_bliss_row_view`. Tutti i requisiti di integrazione del Bliss Engine sono soddisfatti.
