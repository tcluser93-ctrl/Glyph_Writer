#!/usr/bin/env python3
"""
tools/bci_names_split.py

Splits the bundled bci_full.json (id -> {"langs": {"it": [...], "en": [...],
...}}, 17 languages, 3.75 MB) into one compact per-app-language asset:
    app/src/main/assets/bliss/bci_names_<lang>.json   {"8483": "punto esclamativo", ...}

## Why this exists (audit EG, 2026-07-22)
BlissLookup.loadNames() only ever read bci_names.json — an English-only
id -> name map — regardless of the active translation language, so every
symbol's displayed gloss (card_gloss, chip text, TTS) was always in English
even when translating into Italian, German, etc. bci_full.json already
carries the per-language names needed to fix this, but was never wired up
(flagged as a known follow-up since before this audit began).

Loading the *entire* 3.75 MB / 17-language bci_full.json at runtime on every
language switch just to use 1/17th of it would work, but wastes parse time
and peak memory for no benefit. Splitting into small per-language files at
build time — the same pattern already used for the WordNet Stage A assets
(see tools/wordnet_build.py) — keeps BlissLookup.loadNames(lang) as cheap as
every other per-language asset it already loads.

## Data quality note: Polish
bci_full.json's `langs` dict uses the key "po" for Polish, not the correct
ISO-639-1 "pl" this app uses everywhere else (SUPPORTED_LANGS, the
bci_lexicon_pl.json / lemmas_pl.csv asset names, tools/wordnet_build.py's
OMW_LANG_DIR). Verified empirically: "pl" has 0/6419 entries in bci_full.json
while "po" has 4514/6419, and every "po" entry inspected is genuine Polish
text (e.g. "znak zapytania" = "question mark") — an upstream data quirk, not
a real absence of Polish coverage. POLISH_SOURCE_KEY below documents and
localises this one-off translation.

## Fallback strategy
Not every symbol has a translation for every language (coverage ranges from
~61% for Italian/Portuguese to 100% for English in the current data). Every
id is guaranteed to have an English entry, so a missing target-language name
falls back to English rather than being silently dropped — BlissLookup's
public contract (Map<Int, String>, every value non-blank) is unaffected
either way, and the alternative (skipping the id entirely for that language)
would silently make some symbols un-lookup-by-name for no benefit, since the
symbol is still a perfectly valid, existing BCI-AV entry.

## Usage
    python3 tools/bci_names_split.py --lang it --lang en --lang de --lang fr \\
        --lang es --lang nl --lang pl --lang pt
    # or, to (re)generate every app-supported language in one call:
    python3 tools/bci_names_split.py --all
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BCI_FULL_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "bliss" / "bci_full.json"
OUTPUT_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "bliss"

# App language code -> key used inside bci_full.json's "langs" object.
# Identical for every language except Polish — see the module docstring's
# "Data quality note" for why "po" is correct here, not a typo.
POLISH_SOURCE_KEY = "po"
ALL_APP_LANGS = ["it", "en", "de", "fr", "es", "nl", "pl", "pt"]


def source_key(app_lang: str) -> str:
    return POLISH_SOURCE_KEY if app_lang == "pl" else app_lang


def build_names_for_lang(full: dict, app_lang: str) -> dict[str, str]:
    key = source_key(app_lang)
    out: dict[str, str] = {}
    missing = 0
    for bci_id, entry in full.items():
        langs = entry.get("langs", {})
        names = langs.get(key)
        if names:
            out[bci_id] = names[0]
            continue
        # Fallback: top-level "en" field is present on every entry (verified:
        # 6419/6419 ids have both this and langs["en"] populated identically).
        fallback = entry.get("en") or (langs.get("en") or [None])[0]
        if fallback:
            out[bci_id] = fallback
            missing += 1
        # If even the English fallback is somehow absent, the id is skipped
        # rather than emitting an empty-string name — BlissLookup.nameOf()
        # already handles an id absent from `names` by falling back to
        # id.toString(), which is a safer default than a blank gloss.
    if missing:
        print(f"  [{app_lang}] {missing}/{len(full)} ids used the English fallback "
              f"(no '{key}' entry in bci_full.json)")
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", action="append", dest="langs", default=[],
                     help="app language code (it, en, de, fr, es, nl, pl, pt); repeatable")
    ap.add_argument("--all", action="store_true", help="build every app-supported language")
    args = ap.parse_args()
    langs = ALL_APP_LANGS if args.all else args.langs
    if not langs:
        raise SystemExit("Specify --lang <code> (repeatable) or --all")

    print(f"Loading {BCI_FULL_PATH.relative_to(REPO_ROOT)} ...")
    with BCI_FULL_PATH.open(encoding="utf-8") as f:
        full = json.load(f)
    print(f"  {len(full)} BCI-AV entries")

    for lang in langs:
        if lang not in ALL_APP_LANGS:
            raise SystemExit(f"--lang {lang}: not one of {ALL_APP_LANGS}")
        names = build_names_for_lang(full, lang)
        out_path = OUTPUT_DIR / f"bci_names_{lang}.json"
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(names, f, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        print(f"  wrote {out_path.relative_to(REPO_ROOT)}: {len(names)} entries, "
              f"{out_path.stat().st_size / 1024:.1f} KB")

    print("\nDone. Remember to git add app/src/main/assets/bliss/bci_names_*.json.")
    print("bci_names.json (the old English-only file) can be removed once BlissLookup")
    print("is switched over to bci_names_<lang>.json — see the paired Kotlin change.")


if __name__ == "__main__":
    main()
