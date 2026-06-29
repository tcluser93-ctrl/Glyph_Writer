# Integrazione Bliss → ThothView

## Panoramica architetturale

```
BlissTranslateFragment
        │
        ▼
   BlissTranslator  ──(NLP)──▶  List<BlissSymbol>
        │
        ▼
  BlissGlyphXBuilder  ──────▶  org.w3c.dom.Document  (GlyphX con codici "B{id}")
        │
        ▼
   BlissViewModel.postTranslation(symbols, doc)
        │
        ├──▶  BlissRenderer.render(symbols, provider)   (anteprima inline nel Fragment)
        │
        └──▶  DocumentEditorActivity  (observer LiveData)
                   │
                   ▼
             thothView.setGlyphXText(doc)
                   │
                   ▼
        ThothView chiama SignProvider.getDrawable("B12335")
                   │
                   ▼
          BlissSignProvider  ──▶  assets/bliss/svg/12335.svg
```

---

## Step 1 — Aggiungere AndroidSVG

In `app/build.gradle.kts`, aggiungere:

```kotlin
implementation("com.caverock:androidsvg-aar:1.4")
```

---

## Step 2 — Scaricare gli SVG BCI-AV

Il corpus SVG è disponibile su:
- https://www.blissymbolics.org/index.php/licensing
- https://openassistive.org/item/blissymbolicsresources/ (CC BY-SA)

Decomprimere i file SVG in:
```
app/src/main/assets/bliss/svg/
    12335.svg
    17729.svg
    ...
```
Ogni file è nominato con il BCI-AV ID numerico.

---

## Step 3 — Collegare BlissSignProvider a ThothView

ThothView (da `com.github.ThothDroid:SignProvider`) espone un metodo
`setSignProvider(provider: SignProvider)`. La API esatta dipende dalla
versione della libreria.

**Opzione A — SignProvider iniettabile (se supportato)**

Se `ThothView` ha `setSignProvider()`, creare un wrapper:

```kotlin
// In DocumentEditorActivity.onCreate(), dopo thothView.setThothListener(this):
val blissProvider = BlissSignProvider(this)
// Wrapper che implementa l'interfaccia SignProvider di ThothDroid
thothView.setSignProvider(BlissSignProviderAdapter(blissProvider))
```

Dove `BlissSignProviderAdapter` implementa l'interfaccia
`com.blueapps.signprovider.SignProvider` (verifica la firma esatta
inspezionando l'AAR con `javap` o Android Studio).

**Opzione B — Fallback BlissRenderer (standalone, non dipende da ThothView)**

Se ThothView non espone un SignProvider esterno, usare `BlissRenderer`
direttamente nel Fragment come anteprima; il documento GlyphX viene comunque
passato a ThothView per l'export/salvataggio (i codici B{id} saranno visibili
come label di testo nel renderer nativo di ThothView).

---

## Step 4 — Terzo tab in DocumentEditorActivity

**Nel layout XML** (`activity_document_editor.xml`):
```xml
<!-- Aggiungere un terzo CheckableImageButton accanto a buttonWrite e buttonSettings -->
<com.blueapps.egyptianwriter.CheckableImageButton
    android:id="@+id/buttonBliss"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:src="@drawable/ic_bliss"
    android:contentDescription="Traduttore Bliss" />
```

**In `DocumentEditorActivity.java`**:
```java
// Aggiungere a imageButtonGroup:
imageButtonGroup.addImageButton(binding.buttonBliss);

// Nel metodo OnPositionChanges:
case 2:
    BlissViewModel blissVm =
        new ViewModelProvider(this).get(BlissViewModel.class);
    blissVm.getGlyphXDocument().observe(this, doc -> {
        try { thothView.setGlyphXText(doc); }
        catch (Exception e) { e.printStackTrace(); }
    });
    transaction.replace(containerView.getId(),
        BlissTranslateFragment.Companion.newInstance("it"));
    break;
```

---

## Step 5 — Icona Bliss

Aggiungere in `app/src/main/res/drawable/ic_bliss.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <!-- Simbolo Bliss semplificato: cerchio + triangolo + linea -->
  <path android:fillColor="@color/bliss_icon"
        android:pathData="M12,2 C9.79,2 8,3.79 8,6 C8,8.21 9.79,10 12,10
                         C14.21,10 16,8.21 16,6 C16,3.79 14.21,2 12,2 Z"/>
  <path android:fillColor="@color/bliss_icon"
        android:pathData="M6,22 L12,12 L18,22 Z"/>
  <path android:strokeColor="@color/bliss_icon"
        android:strokeWidth="2"
        android:pathData="M4,16 H20"/>
</vector>
```

---

## Riepilogo file del modulo `bliss/`

| File | Ruolo |
|---|---|
| `BlissSymbol.kt` | Data model (bciAvId, gloss, matchType, word) |
| `BlissLookup.kt` | Carica asset CSV/JSON, 5 tabelle lookup |
| `BlissTranslator.kt` | Pipeline NLP → List<BlissSymbol> |
| `BlissGlyphXBuilder.kt` | List<BlissSymbol> → GlyphX DOM Document |
| `BlissViewModel.kt` | LiveData bridge Fragment ↔ Activity |
| `BlissTranslateFragment.kt` | UI con chip colorati, input testo |
| `BlissSignProvider.kt` | Risolve codice B{id} → Drawable SVG da assets |
| `BlissRenderer.kt` | View standalone per anteprima simboli |
| `INTEGRATION.md` | Queste istruzioni |
