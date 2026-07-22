#!/usr/bin/env python3
"""
tools/wordnet_build.py

Builds the compact WordNet-derived asset files that power
BlissSemanticComposer's redesigned Stage A: substituting a word with no
direct Bliss symbol by a synonym or hypernym that *does* have one
(e.g. Italian "oceano" -> "mare"), rather than falling straight to
UNKNOWN.

Design rationale, coverage measurements, and the "why" behind every
decision in this script (POS filtering, hypernym depth cap, licensing)
are documented in the EG audit report:
    Report_EG_Tier3g_Opzioni_A_D.md (section 7, "Fase 0").
Read that first if anything here looks arbitrary.

## Data sources
- Open Multilingual Wordnet (omwn/omw-data on GitHub): per-language
  word <-> Princeton WordNet 3.0 synset tables ("wn-data-<lang>.tab").
  Each language file is released under its own open license (CC BY /
  CC BY-SA / public domain depending on the underlying project) -- see
  the generated ATTRIBUTIONS.md for the exact citation per language
  actually bundled.
- Princeton WordNet 3.0 hypernym graph, fetched via the `wn` Python
  library (`pip install wn`, then `wn.download('omw-en:1.4')` once).
  Synset ids are shared across all OMW languages (they're PWN 3.0
  offsets), so the hypernym graph itself is language-independent and
  is emitted once, not once per language.

## Usage
    pip install wn --break-system-packages   # first time only
    python3 -c "import wn; wn.download('omw-en:1.4')"   # first time only
    python3 tools/wordnet_build.py --lang it --lang en

## Outputs (written into app/src/main/assets/wordnet/)
    word2synsets_<lang>.json   "word" -> ["13776971-n", ...]
                               File order == first-sense order (the
                               order senses appear in the source OMW
                               tab file), used as a cheap proxy for
                               "most common sense first" since OMW does
                               not carry explicit frequency ranks.
    synset2bliss_<lang>.json   "13776971-n" -> [12335, 14990, ...]
                               Precomputed at build time: BCI-AV ids of
                               every Bliss-lexicon word that is itself
                               a lemma of that synset, *for this
                               language*. Only synsets with >=1 hit are
                               included (the vast majority have none).
    hypernyms.json             "13776971-n" -> ["09210529-n", ...]
                               SHARED across all languages. Nouns and
                               verbs only -- WordNet adjectives/adverbs
                               use "similar_to", not a clean is-a
                               hypernym chain, so climbing them would
                               not carry the "X is a kind of Y"
                               guarantee Stage A relies on.
    ATTRIBUTIONS.md            Regenerated to cover the union of every
                               language this script has ever been run
                               for (reads any existing file first).
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "wordnet"
BLISS_LEXICON_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "bliss"

OMW_DATA_RAW = "https://raw.githubusercontent.com/omwn/omw-data/main/wns"

# App language code -> OMW wordnet directory name (they don't always match:
# OMW groups Spanish/Catalan/Basque/Galician under the shared "mcr" project).
OMW_LANG_DIR = {
    "it": "ita",
    "en": "eng",
    "fr": "fra",
    "nl": "nld",
    "pl": "pol",
    "pt": "por",
    "es": "mcr",
}
OMW_TAB_FILE = {
    "it": "wn-data-ita.tab",
    "en": "wn-data-eng.tab",
    "fr": "wn-data-fra.tab",
    "nl": "wn-data-nld.tab",
    "pl": "wn-data-pol.tab",
    "pt": "wn-data-por.tab",
    "es": "wn-data-spa.tab",
}
# The language tag used *inside* each tab file's relation column
# ("<tag>:lemma"). Usually the same 3-letter code as the filename, but
# English is the reference wordnet and uses bare "lemma" with no prefix.
OMW_TAG = {
    "it": "ita",
    "en": None,  # bare "lemma", no language prefix
    "fr": "fra",
    "nl": "nld",
    "pl": "pol",
    "pt": "por",
    "es": "spa",
}
# German is not in omw-data 1.x (OdeNet ships separately in OMW 2.0 / `wn`);
# left as a documented follow-up rather than silently skipped.
UNSUPPORTED = {"de": "OdeNet not available via omw-data; needs a separate fetch path (see report, Fase 0 table)."}

NOUN_VERB_SUFFIXES = ("-n", "-v")


def fetch_tab_file(lang: str) -> str:
    if lang in UNSUPPORTED:
        raise SystemExit(f"--lang {lang}: {UNSUPPORTED[lang]}")
    if lang not in OMW_LANG_DIR:
        raise SystemExit(f"--lang {lang}: unknown app language code")
    url = f"{OMW_DATA_RAW}/{OMW_LANG_DIR[lang]}/{OMW_TAB_FILE[lang]}"
    print(f"[{lang}] downloading {url}")
    with urllib.request.urlopen(url, timeout=60) as resp:
        return resp.read().decode("utf-8")


def parse_tab(text: str, omw_tag: str) -> tuple[dict[str, list[str]], dict[str, set[str]]]:
    """Returns (word -> [synset,...] in file order, synset -> {word,...}).

    `omw_tag` is the OMW-internal language code used inside the tab file's
    relation column (e.g. "ita", "eng" — ISO 639-2/3), which does NOT
    always match the app's 2-letter ISO 639-1 code passed on the command
    line (that mapping lives in OMW_LANG_DIR).
    """
    word2syn: dict[str, list[str]] = collections.defaultdict(list)
    syn2words: dict[str, set[str]] = collections.defaultdict(set)
    tag = "lemma" if omw_tag is None else f"{omw_tag}:lemma"
    for line in text.splitlines():
        if line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3 or parts[1] != tag:
            continue
        synset, word = parts[0], parts[2].lower()
        if synset not in word2syn[word]:
            word2syn[word].append(synset)
        syn2words[synset].add(word)
    return word2syn, syn2words


def load_bliss_lexicon(lang: str) -> dict[str, int]:
    """Loads bci_lexicon_<lang>.json as word -> primary BCI-AV id.

    Every entry in this file is a JSON array of candidate ids (e.g.
    "punto": [8486, 13867] for an ambiguous word) -- see the KDoc on
    BlissLookup.firstBciId() in the main Kotlin source for the full
    story (this was, until the matching EG-audit fix, silently
    misparsed by the app itself). This loader takes the first
    candidate as primary, exactly like that Kotlin fix does, so the
    WordNet-derived Bliss ids stay consistent with what the app itself
    resolves a lexicon entry to at runtime.
    """
    path = BLISS_LEXICON_DIR / f"bci_lexicon_{lang}.json"
    if not path.exists():
        raise SystemExit(f"--lang {lang}: {path} not found (no Bliss lexicon for this language)")
    with path.open(encoding="utf-8") as f:
        raw = json.load(f)
    out: dict[str, int] = {}
    for word, value in raw.items():
        if isinstance(value, list):
            if value:
                out[word.lower()] = int(value[0])
        elif isinstance(value, (int, float)):
            out[word.lower()] = int(value)
    return out


def build_synset2bliss(syn2words: dict[str, set[str]], bliss_lexicon: dict[str, int]) -> dict[str, list[int]]:
    out: dict[str, list[int]] = {}
    for synset, words in syn2words.items():
        ids = sorted({bliss_lexicon[w] for w in words if w in bliss_lexicon})
        if ids:
            out[synset] = ids
    return out


def build_hypernyms(all_synsets: set[str]) -> dict[str, list[str]]:
    """Fetches direct hypernyms for every noun/verb synset in `all_synsets`
    from the local `wn` PWN 3.0 database. Requires
    `wn.download('omw-en:1.4')` to have been run once already."""
    try:
        import wn
    except ImportError:
        raise SystemExit(
            "The 'wn' package is required to build hypernyms.json.\n"
            "  pip install wn --break-system-packages\n"
            "  python3 -c \"import wn; wn.download('omw-en:1.4')\""
        )
    en = wn.Wordnet("omw-en:1.4")
    out: dict[str, list[str]] = {}
    targets = [s for s in all_synsets if s.endswith(NOUN_VERB_SUFFIXES)]
    print(f"resolving hypernyms for {len(targets)} noun/verb synsets via PWN 3.0 ...")
    for i, synset in enumerate(targets):
        if i and i % 5000 == 0:
            print(f"  ... {i}/{len(targets)}")
        try:
            ss = en.synset(f"omw-en-{synset}")
            hyps = [h.id.replace("omw-en-", "") for h in ss.hypernyms()]
        except Exception:
            hyps = []
        if hyps:
            out[synset] = hyps
    return out


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        # Compact separators: this ships in the APK, every byte counts.
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    print(f"  wrote {path.relative_to(REPO_ROOT)} ({path.stat().st_size / 1024:.1f} KB)")


ATTRIBUTION_TEXT = {
    "it": "MultiWordNet (Italian) — Fondazione Bruno Kessler — CC BY 3.0 — https://multiwordnet.fbk.eu/",
    "en": "Open English Wordnet, derived from Princeton WordNet 3.0 — CC BY 4.0 — https://wordnet.princeton.edu/",
    "fr": "WOLF (Wordnet Libre du Français) — CC BY-SA 3.0 — via Open Multilingual Wordnet",
    "nl": "Open Dutch Wordnet — CC BY-SA — via Open Multilingual Wordnet",
    "pl": "plWordNet — CC BY-SA — via Open Multilingual Wordnet",
    "pt": "OpenWN-PT — CC BY-SA — via Open Multilingual Wordnet",
    "es": "Multilingual Central Repository (Spanish) — CC BY 3.0 — via Open Multilingual Wordnet",
}


def update_attributions(langs_processed: list[str]) -> None:
    path = ASSETS_DIR / "ATTRIBUTIONS.md"
    existing: set[str] = set()
    if path.exists():
        existing = {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.startswith("- ")}
    for lang in langs_processed:
        existing.add(f"- {ATTRIBUTION_TEXT[lang]}")
    header = (
        "# Third-party wordnet data attributions\n\n"
        "Generated by tools/wordnet_build.py. This app's semantic-composition\n"
        "tier (BlissSemanticComposer Stage A) uses word/synset data derived\n"
        "from the following open wordnets, aggregated via the Open\n"
        "Multilingual Wordnet project (Bond & Paik 2012; Bond & Foster 2013).\n"
        "Hypernym relations are derived from Princeton WordNet 3.0.\n\n"
    )
    path.write_text(header + "\n".join(sorted(existing)) + "\n", encoding="utf-8")
    print(f"  wrote {path.relative_to(REPO_ROOT)}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", action="append", required=True, dest="langs",
                     help="app language code (it, en, fr, nl, pl, pt, es); repeatable")
    args = ap.parse_args()

    all_synsets_for_hypernyms: set[str] = set()
    # Load any hypernyms.json already on disk so re-running for a subset
    # of languages doesn't lose synsets resolved by a previous run.
    hyper_path = ASSETS_DIR / "hypernyms.json"
    hypernyms: dict[str, list[str]] = {}
    if hyper_path.exists():
        hypernyms = json.loads(hyper_path.read_text(encoding="utf-8"))

    per_lang_synsets: dict[str, set[str]] = {}
    for lang in args.langs:
        print(f"\n=== {lang} ===")
        tab_text = fetch_tab_file(lang)
        word2syn, syn2words = parse_tab(tab_text, OMW_TAG[lang])
        print(f"  {len(word2syn)} lemmas, {len(syn2words)} synsets")

        bliss_lexicon = load_bliss_lexicon(lang)
        synset2bliss = build_synset2bliss(syn2words, bliss_lexicon)
        print(f"  {len(synset2bliss)} synsets have >=1 Bliss-lexicon match")

        write_json(ASSETS_DIR / f"word2synsets_{lang}.json", word2syn)
        write_json(ASSETS_DIR / f"synset2bliss_{lang}.json", synset2bliss)

        per_lang_synsets[lang] = set(syn2words.keys())
        all_synsets_for_hypernyms |= per_lang_synsets[lang]

    print(f"\n=== hypernyms (shared) ===")
    new_hypernyms = build_hypernyms(all_synsets_for_hypernyms)
    hypernyms.update(new_hypernyms)
    write_json(hyper_path, hypernyms)

    print(f"\n=== attributions ===")
    update_attributions(args.langs)

    print("\nDone. Remember to git add app/src/main/assets/wordnet/*.")


if __name__ == "__main__":
    main()
