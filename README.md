[![GitHub Repo stars](https://img.shields.io/github/stars/tcluser93-ctrl/Glyph_Writer?style=for-the-badge&logo=github&color=yellowgreen)](https://github.com/tcluser93-ctrl/Glyph_Writer)
[![GitHub License](https://img.shields.io/github/license/tcluser93-ctrl/Glyph_Writer?style=for-the-badge&logo=gnu&color=yellow)](https://github.com/tcluser93-ctrl/Glyph_Writer?tab=GPL-3.0-1-ov-file)
[![GitHub forks](https://img.shields.io/github/forks/tcluser93-ctrl/Glyph_Writer?style=for-the-badge&logo=git&logoColor=white&color=%23F05032)](https://github.com/tcluser93-ctrl/Glyph_Writer)
[![GitHub code size in bytes](https://img.shields.io/github/languages/code-size/tcluser93-ctrl/Glyph_Writer?color=blue)](https://github.com/tcluser93-ctrl/Glyph_Writer)

# Bliss Writer — Glyph Writer fork

**An Android app for composing, translating and exporting text using Blissymbols (BCI-AV standard).**

This project is a fork of [Egyptian Writer](https://github.com/ThothDroid/Egyptian_Writer) by ThothDroid, extended with a full **NLP translation pipeline** that converts natural language (Italian, English, German, French, Spanish, Dutch and more) into [Blissymbolics](https://www.blissymbolics.org/) — a universal graphical language used in augmentative and alternative communication (AAC).

---

## What is Blissymbolics?

Blissymbolics is a semantic graphical language originally designed by Charles K. Bliss. Each concept is represented by a symbol (a *bliss glyph*) identified by a BCI-AV numeric code. The system is widely used in AAC (Augmentative and Alternative Communication) for people with speech or language disabilities.

This app allows anyone to:
- **Type text** in a supported natural language
- **Translate it** into a sequence of Bliss symbols via a 5-tier NLP pipeline
- **View** the resulting glyphs as an SVG composition
- **Export and share** the output as SVG or PNG

---

## Architecture Overview

### NLP Translation Pipeline (`BlissTranslator`)

The core of the app is a coroutine-safe 5-tier translation pipeline:

| Tier | Strategy | Description |
|------|----------|-------------|
| 1 | `EXACT` | Direct surface-form lookup in BCI lexicon |
| 2 | `LEMMA` | Lemmatized lookup via Morfologik FSA dictionaries |
| 3 | `MORFOLOGIK` | Morfologik stemmer fallback for inflected forms |
| 4 | `DEAFFIX` | Prefix/suffix stripping heuristic |
| 5 | `UNKNOWN` | Category fallback with nearest-neighbor synset |

Each result is tagged with its match type, which determines chip color in the SVG output.

### Key Components

| Component | File | Role |
|-----------|------|------|
| `BlissTranslator` | `bliss/BlissTranslator.kt` | 5-tier async pipeline, `translateAsync()` coroutine |
| `BlissLookup` | `bliss/BlissLookup.kt` | Asset loader + lexicon index (8 languages, Room FTS5) |
| `MorfologikLemmatizer` | `bliss/MorfologikLemmatizer.kt` | FSA lemmatizer for IT/EN/DE, coroutine-safe with `Mutex` |
| `BlissGlyphXBuilder` | `bliss/BlissGlyphXBuilder.kt` | DOM builder for GlyphX XML documents; `toSvgBytes()` / `toSvgString()` |
| `BlissTranslateFragment` | `ui/BlissTranslateFragment.kt` | UI: Spinner lang, predictive suggestions, FlexboxLayout chip grid, FAB share |
| `BlissViewModel` | `ui/BlissViewModel.kt` | StateFlow UI state, coroutine lifecycle, translation stats |
| `SuggestionAdapter` | `ui/SuggestionAdapter.kt` | `ListAdapter<String>` with DiffUtil for predictive chip suggestions |

### Asset Structure

```
app/src/main/assets/
├── bliss/
│   ├── bci_names.json          # BCI-AV ID → symbol name map (6419 entries)
│   ├── bci_lexicon_it.json     # Italian surface forms → BCI-AV IDs
│   ├── bci_lexicon_en.json     # English
│   ├── bci_lexicon_de.json     # German
│   ├── bci_lexicon_fr.json     # French
│   ├── bci_lexicon_es.json     # Spanish
│   ├── bci_lexicon_nl.json     # Dutch
│   ├── bci_synsets.json        # Synset groupings for fallback
│   └── ...                     # Additional lexicons
└── morfologik/
    ├── it.dict / it.info       # Morfologik FSA — Italian
    ├── en.dict / en.info       # Morfologik FSA — English
    └── de.dict / de.info       # Morfologik FSA — German
```

---

## Development Status

### ✅ Completed

| Phase | Content |
|-------|---------|
| **Phase 1–4 — Core NLP** | `BlissLookup`, `BlissTranslator`, `BlissGlyphXBuilder`, `MorfologikLemmatizer` — all refactored, coroutine-safe |
| **Phase 5 — UI/UX** | `BlissTranslateFragment`: language Spinner, predictive suggestions RecyclerView, FlexboxLayout chip grid, FAB SVG share via FileProvider |
| **Accessibility** | `contentDescription` on all widgets, `announceForAccessibility` on output, Material chip 48dp touch targets |
| **i18n** | `strings.xml` + `values-it/strings.xml` — zero hardcoded strings in Fragment |
| **SVG Export** | `toSvgBytes()` / `toSvgString()` — adaptive canvas, match-type chip colors, plural/tense badges, XML-safe escaping |
| **Morfologik dictionaries** | `it.dict`, `en.dict`, `de.dict` pushed to `assets/morfologik/` |
| **Unit tests — 49 total** | `BlissLookupTest` (22), `BlissViewModelTest` (18), `TranslationStatsTest` (9) — pure JVM, no emulator needed |
| **CI workflow** | `.github/workflows/unit-tests.yml` — manual trigger, JUnit XML + HTML artifacts, dorny Checks annotation, Markdown Job Summary |

### 🔴 Pending / Next Steps

| Task | Priority | Notes |
|------|----------|-------|
| **BCI asset files** (real JSON data) | 🔴 Blocker | Placeholder files present; real BCI-AV data needed for runtime |
| **Predictive suggestions** wiring | 🟡 High | `lookupPrefixDb()` ready, needs TextWatcher binding |
| **History panel** (RecyclerView) | 🟡 High | Room already present, needs `HistoryDao` |
| **TalkBack full audit** | 🟡 High | `traversalBefore/After`, live regions on output |
| **Switch Access** focus order | 🟠 Medium | All interactive widgets need correct focus chain |
| **Dynamic font size** (sp/fontScale) | 🟠 Medium | Some dimensions still hardcoded in px |
| **ProGuard validation** | 🟠 Medium | Keep rules written, not yet validated on release APK |
| **Signed APK + GitHub Release** | 🟠 Medium | CI builds debug APK only; keystore workflow needed |
| **Phase 6 — Rendering** | 🔵 Planned | RecyclerView with live SVG chip preview during translation |
| **Integration tests** (androidTest) | 🔵 Optional | Full `loadAssets → translate → render` cycle on emulator |

---

## Running the Tests

```bash
# All local JVM tests (no device or emulator needed)
./gradlew :app:test

# Only Bliss-related tests
./gradlew :app:test --tests "com.blueapps.egyptianwriter.bliss.*"

# HTML report:
# app/build/reports/tests/testDebugUnitTest/index.html
```

---

## Building

```bash
# Debug APK
./gradlew :app:assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

**Requirements:** JDK 21, Android SDK platform 36, Gradle 8.x.

---

## Installation

Download the latest debug APK from the [Releases](https://github.com/tcluser93-ctrl/Glyph_Writer/releases) page or directly from the repo root:

- [Egyptian_Writer.apk](https://github.com/tcluser93-ctrl/Glyph_Writer/raw/main/Egyptian_Writer.apk) _(upstream build — Bliss features not yet in this binary)_

---

## Dependencies & Licenses

| Library | License |
|---------|---------|
| AndroidX Room + FTS5 | Apache 2.0 |
| AndroidX Lifecycle / ViewModel | Apache 2.0 |
| Material Components (Flexbox) | Apache 2.0 |
| Morfologik Stemming | BSD 2-Clause |
| LanguageTool dictionaries (IT/EN/DE) | LGPL 2.1 |
| Kotlin Coroutines | Apache 2.0 |

For the full upstream dependency list see [`LicenseCompatibility.md`](/LicenseCompatibility.md).

---

## Upstream Project

This fork is based on **Egyptian Writer** by [ThothDroid](https://github.com/ThothDroid/Egyptian_Writer) — an app for viewing, creating and exporting ancient Egyptian hieroglyphs. The original hieroglyph functionality is preserved; the Bliss NLP layer is additive.

- Original repo: [ThothDroid/Egyptian_Writer](https://github.com/ThothDroid/Egyptian_Writer)
- Upstream libraries: [MAAT](https://github.com/ThothDroid/MAAT), [THOTH](https://github.com/ThothDroid/THOTH), [GlyphConverter](https://github.com/ThothDroid/GlyphConverter), [SignProvider](https://github.com/ThothDroid/SignProvider)

---

## Contributing

- ⭐ Star the repo if you find it useful
- 🐛 Open an issue for bugs or feature requests
- 🔀 PRs welcome — please open an issue first for significant changes
- 📧 Feedback: [website.tutorials@gmx.de](mailto:website.tutorials@gmx.de)

---

## Version History

| Date | Version | Notes |
|------|---------|-------|
| 10.01.2025 | 0.0.1 | First release of Egyptian Writer |
| 29.01.2026 | 0.0.3 | Gradle upgrade, .ewdoc import fix, small screen support |
| 17.02.2026 | 0.1.0 | Navigation drawer, SignList page, About page, SignProvider migration |
| 28.06.2026 | 0.2.0-dev | Fork: Bliss NLP pipeline added — BlissTranslator, BlissLookup, MorfologikLemmatizer |
| 03.07.2026 | 0.3.0-dev | UI complete: language Spinner, suggestions, FlexboxLayout, SVG share; 49 unit tests; CI workflow |

---

_Blissymbolics symbols are the intellectual property of Blissymbolics Communication International (BCI). See [blissymbolics.org](https://www.blissymbolics.org) for licensing._
