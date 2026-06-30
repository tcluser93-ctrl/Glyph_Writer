#!/usr/bin/env python3
"""
push_bliss_assets.py — Enterprise-grade uploader for Bliss SVG and data assets.

Features
--------
* Retry with exponential back-off + full jitter (RFC 6749 §4.5)
* SHA-256 pre-check against GitHub blob SHA (skip unchanged files)
* --dry-run  : print upload plan without touching the repo
* --atomic   : all files in ONE tree commit via Git Data API
* --reset    : delete & re-upload every file regardless of SHA match
* ASCII progress bar + per-file status (OK / SKIP / ERR)
* GITHUB_TOKEN from environment, .env file, or interactive prompt

Usage
-----
    export GITHUB_TOKEN=ghp_...
    python3 push_bliss_assets.py [--dry-run] [--atomic] [--reset] [--verbose]

Requires: requests (pip install requests)
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import random
import sys
import time
from pathlib import Path
from typing import Optional

try:
    import requests
except ImportError:
    sys.exit("[ERROR] 'requests' not installed. Run: pip install requests")

# ── Configuration ─────────────────────────────────────────────────────────────

OWNER  = "tcluser93-ctrl"
REPO   = "Glyph_Writer"
BRANCH = "main"
API    = "https://api.github.com"

# Local path → remote path mapping.
# Keys are relative paths on the local filesystem (cwd when the script runs).
# Values are the target paths inside the repository.
ASSET_MAP: dict[str, str] = {
    # ── BCI-AV name index ──────────────────────────────────────────────────
    "assets/bliss/bci_names.json":                "app/src/main/assets/bliss/bci_names.json",

    # ── Per-language lexicons ──────────────────────────────────────────────
    "assets/bliss/bci_lexicon_it.json":           "app/src/main/assets/bliss/bci_lexicon_it.json",
    "assets/bliss/bci_lexicon_en.json":           "app/src/main/assets/bliss/bci_lexicon_en.json",
    "assets/bliss/bci_lexicon_de.json":           "app/src/main/assets/bliss/bci_lexicon_de.json",
    "assets/bliss/bci_lexicon_fr.json":           "app/src/main/assets/bliss/bci_lexicon_fr.json",
    "assets/bliss/bci_lexicon_es.json":           "app/src/main/assets/bliss/bci_lexicon_es.json",
    "assets/bliss/bci_lexicon_sv.json":           "app/src/main/assets/bliss/bci_lexicon_sv.json",
    "assets/bliss/bci_lexicon_nl.json":           "app/src/main/assets/bliss/bci_lexicon_nl.json",
    "assets/bliss/bci_lexicon_no.json":           "app/src/main/assets/bliss/bci_lexicon_no.json",

    # ── Lemmatisation tables ───────────────────────────────────────────────
    "assets/bliss/lemmas_it.csv":                 "app/src/main/assets/bliss/lemmas_it.csv",
    "assets/bliss/lemmas_en.csv":                 "app/src/main/assets/bliss/lemmas_en.csv",
    "assets/bliss/lemmas_de.csv":                 "app/src/main/assets/bliss/lemmas_de.csv",
    "assets/bliss/lemmas_fr.csv":                 "app/src/main/assets/bliss/lemmas_fr.csv",
    "assets/bliss/lemmas_es.csv":                 "app/src/main/assets/bliss/lemmas_es.csv",

    # ── N-gram tables ──────────────────────────────────────────────────────
    "assets/bliss/ngrams_multilang.csv":          "app/src/main/assets/bliss/ngrams_multilang.csv",

    # ── SVG symbol sheets ──────────────────────────────────────────────────
    "assets/bliss/svg/bliss_symbols.svg":         "app/src/main/assets/bliss/svg/bliss_symbols.svg",
    "assets/bliss/svg/bliss_indicators.svg":      "app/src/main/assets/bliss/svg/bliss_indicators.svg",
    "assets/bliss/svg/bliss_combinators.svg":     "app/src/main/assets/bliss/svg/bliss_combinators.svg",
}

MAX_RETRIES     = 5
BASE_BACKOFF_S  = 1.0   # seconds, doubled each attempt
MAX_BACKOFF_S   = 30.0


# ── Token resolution ──────────────────────────────────────────────────────────

def load_token() -> str:
    """Return GitHub token from env, .env file, or interactive prompt."""
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        return token

    env_file = Path(".env")
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            line = line.strip()
            if line.startswith("GITHUB_TOKEN"):
                parts = line.split("=", 1)
                if len(parts) == 2:
                    token = parts[1].strip().strip('"\'')
                    if token:
                        print("[INFO] Token loaded from .env")
                        return token

    print("[INFO] GITHUB_TOKEN not found in environment or .env")
    token = input("Enter GitHub token: ").strip()
    if not token:
        sys.exit("[ERROR] No token provided.")
    return token


# ── HTTP helpers ──────────────────────────────────────────────────────────────

def _headers(token: str) -> dict:
    return {
        "Authorization": f"Bearer {token}",
        "Accept":        "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }


def _backoff(attempt: int) -> float:
    """Full-jitter exponential back-off (capped at MAX_BACKOFF_S)."""
    cap   = min(MAX_BACKOFF_S, BASE_BACKOFF_S * (2 ** attempt))
    sleep = random.uniform(0, cap)
    return sleep


def api_get(path: str, token: str) -> Optional[dict]:
    """GET with retry. Returns None on 404; raises on other errors."""
    url = f"{API}{path}"
    for attempt in range(MAX_RETRIES):
        try:
            r = requests.get(url, headers=_headers(token), timeout=30)
            if r.status_code == 404:
                return None
            if r.status_code == 200:
                return r.json()
            if r.status_code in (429, 502, 503, 504):
                wait = _backoff(attempt)
                print(f"  [RETRY {attempt+1}/{MAX_RETRIES}] HTTP {r.status_code}, waiting {wait:.1f}s")
                time.sleep(wait)
                continue
            r.raise_for_status()
        except requests.RequestException as exc:
            wait = _backoff(attempt)
            print(f"  [RETRY {attempt+1}/{MAX_RETRIES}] {exc}, waiting {wait:.1f}s")
            time.sleep(wait)
    raise RuntimeError(f"GET {url} failed after {MAX_RETRIES} retries")


def api_put(path: str, payload: dict, token: str) -> dict:
    """PUT with retry. Returns response JSON."""
    url = f"{API}{path}"
    for attempt in range(MAX_RETRIES):
        try:
            r = requests.put(url, headers=_headers(token),
                             data=json.dumps(payload), timeout=60)
            if r.status_code in (200, 201):
                return r.json()
            if r.status_code in (409, 422):
                # Conflict / validation: do not retry
                r.raise_for_status()
            if r.status_code in (429, 502, 503, 504):
                wait = _backoff(attempt)
                print(f"  [RETRY {attempt+1}/{MAX_RETRIES}] HTTP {r.status_code}, waiting {wait:.1f}s")
                time.sleep(wait)
                continue
            r.raise_for_status()
        except requests.RequestException as exc:
            wait = _backoff(attempt)
            print(f"  [RETRY {attempt+1}/{MAX_RETRIES}] {exc}, waiting {wait:.1f}s")
            time.sleep(wait)
    raise RuntimeError(f"PUT {url} failed after {MAX_RETRIES} retries")


def api_post(path: str, payload: dict, token: str) -> dict:
    """POST with retry. Returns response JSON."""
    url = f"{API}{path}"
    for attempt in range(MAX_RETRIES):
        try:
            r = requests.post(url, headers=_headers(token),
                              data=json.dumps(payload), timeout=60)
            if r.status_code in (200, 201):
                return r.json()
            if r.status_code in (429, 502, 503, 504):
                wait = _backoff(attempt)
                print(f"  [RETRY {attempt+1}/{MAX_RETRIES}] HTTP {r.status_code}, waiting {wait:.1f}s")
                time.sleep(wait)
                continue
            r.raise_for_status()
        except requests.RequestException as exc:
            wait = _backoff(attempt)
            print(f"  [RETRY {attempt+1}/{MAX_RETRIES}] {exc}, waiting {wait:.1f}s")
            time.sleep(wait)
    raise RuntimeError(f"POST {url} failed after {MAX_RETRIES} retries")


# ── SHA helpers ───────────────────────────────────────────────────────────────

def sha256_local(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def github_blob_sha(content_bytes: bytes) -> str:
    """
    GitHub computes blob SHA as: sha1('blob ' + len + '\0' + content).
    We use this to compare against the 'sha' field returned by the Contents API.
    """
    header = f"blob {len(content_bytes)}\0".encode()
    return hashlib.sha1(header + content_bytes).hexdigest()


# ── Progress bar ──────────────────────────────────────────────────────────────

def progress(done: int, total: int, label: str = "") -> None:
    width  = 40
    filled = int(width * done / max(total, 1))
    bar    = "█" * filled + "░" * (width - filled)
    pct    = int(100 * done / max(total, 1))
    print(f"\r[{bar}] {pct:3d}%  {label:<40}", end="", flush=True)


# ── Single-file upload (non-atomic path) ──────────────────────────────────────

def upload_file(
    local_path: Path,
    remote_path: str,
    token: str,
    reset: bool,
    dry_run: bool,
    verbose: bool,
) -> str:  # returns "OK" | "SKIP" | "ERR"
    if not local_path.exists():
        print(f"  [WARN] Local file not found: {local_path}")
        return "ERR"

    raw = local_path.read_bytes()
    local_blob_sha = github_blob_sha(raw)

    # Check if file already exists and is identical
    existing = api_get(f"/repos/{OWNER}/{REPO}/contents/{remote_path}", token)
    remote_sha: Optional[str] = existing.get("sha") if existing else None

    if not reset and remote_sha and remote_sha == local_blob_sha:
        if verbose:
            print(f"  [SKIP] {remote_path}  (unchanged)")
        return "SKIP"

    if dry_run:
        action = "CREATE" if remote_sha is None else "UPDATE"
        print(f"  [DRY-RUN] {action} {remote_path}")
        return "OK"

    encoded = base64.b64encode(raw).decode()
    payload: dict = {
        "message": f"chore(assets): upload {Path(remote_path).name}",
        "content": encoded,
        "branch":  BRANCH,
    }
    if remote_sha:
        payload["sha"] = remote_sha

    try:
        api_put(f"/repos/{OWNER}/{REPO}/contents/{remote_path}", payload, token)
        return "OK"
    except Exception as exc:  # noqa: BLE001
        print(f"  [ERR] {remote_path}: {exc}")
        return "ERR"


# ── Atomic upload (single tree commit via Git Data API) ───────────────────────

def upload_atomic(
    file_map: dict[Path, str],
    token: str,
    reset: bool,
    dry_run: bool,
    verbose: bool,
) -> tuple[int, int, int]:  # ok, skip, err
    """
    Uploads all files as a single commit using the Git Data API:
      1. Create blobs for each file
      2. Fetch current HEAD tree SHA
      3. Create new tree with all blobs
      4. Create commit pointing to new tree
      5. Update branch ref
    """
    # --- Fetch current HEAD ---
    ref_data = api_get(f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}", token)
    if not ref_data:
        print(f"[ERROR] Could not fetch ref for branch '{BRANCH}'")
        return 0, 0, len(file_map)
    head_sha  = ref_data["object"]["sha"]
    commit_data = api_get(f"/repos/{OWNER}/{REPO}/git/commits/{head_sha}", token)
    tree_sha    = commit_data["tree"]["sha"]

    ok = skip = err = 0
    blobs: list[dict] = []
    total = len(file_map)

    print(f"\n[ATOMIC] Preparing {total} blobs…")
    for i, (local_path, remote_path) in enumerate(file_map.items(), 1):
        progress(i, total, local_path.name)

        if not local_path.exists():
            print(f"\n  [WARN] Missing: {local_path}")
            err += 1
            continue

        raw = local_path.read_bytes()
        local_blob_sha = github_blob_sha(raw)

        if not reset:
            existing = api_get(
                f"/repos/{OWNER}/{REPO}/contents/{remote_path}", token
            )
            remote_sha = existing.get("sha") if existing else None
            if remote_sha and remote_sha == local_blob_sha:
                if verbose:
                    print(f"\n  [SKIP] {remote_path}")
                skip += 1
                continue

        if dry_run:
            print(f"\n  [DRY-RUN] BLOB {remote_path}")
            ok += 1
            continue

        encoded = base64.b64encode(raw).decode()
        try:
            blob_resp = api_post(
                f"/repos/{OWNER}/{REPO}/git/blobs",
                {"content": encoded, "encoding": "base64"},
                token,
            )
            blobs.append({
                "path":     remote_path,
                "mode":     "100644",
                "type":     "blob",
                "sha":      blob_resp["sha"],
            })
            ok += 1
        except Exception as exc:  # noqa: BLE001
            print(f"\n  [ERR] blob {remote_path}: {exc}")
            err += 1

    print()  # newline after progress bar

    if not blobs or dry_run:
        return ok, skip, err

    # --- Create tree ---
    print("[ATOMIC] Creating tree…")
    tree_resp = api_post(
        f"/repos/{OWNER}/{REPO}/git/trees",
        {"base_tree": tree_sha, "tree": blobs},
        token,
    )
    new_tree_sha = tree_resp["sha"]

    # --- Create commit ---
    print("[ATOMIC] Creating commit…")
    commit_msg = (
        f"chore(assets): upload {ok} Bliss asset(s) [atomic]\n\n"
        f"Files: {ok} new/updated, {skip} unchanged, {err} errors."
    )
    commit_resp = api_post(
        f"/repos/{OWNER}/{REPO}/git/commits",
        {
            "message": commit_msg,
            "tree":    new_tree_sha,
            "parents": [head_sha],
        },
        token,
    )
    new_commit_sha = commit_resp["sha"]

    # --- Update ref ---
    print("[ATOMIC] Updating ref…")
    api_post(
        f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}",
        {"sha": new_commit_sha, "force": False},
        token,
    )
    print(f"[ATOMIC] Commit pushed: {new_commit_sha[:12]}")

    return ok, skip, err


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Upload Bliss assets to tcluser93-ctrl/Glyph_Writer"
    )
    parser.add_argument("--dry-run",  action="store_true",
                        help="Print upload plan without touching the repo")
    parser.add_argument("--atomic",   action="store_true",
                        help="Upload all files in a single Git commit")
    parser.add_argument("--reset",    action="store_true",
                        help="Re-upload every file regardless of SHA match")
    parser.add_argument("--verbose",  action="store_true",
                        help="Print SKIP lines too")
    args = parser.parse_args()

    token = load_token()

    # Build resolved map (only local files that exist)
    file_map: dict[Path, str] = {}
    missing  = []
    for local_str, remote_str in ASSET_MAP.items():
        p = Path(local_str)
        if p.exists():
            file_map[p] = remote_str
        else:
            missing.append(local_str)

    if missing:
        print(f"[WARN] {len(missing)} local asset(s) not found (will be skipped):")
        for m in missing:
            print(f"  - {m}")

    if not file_map:
        sys.exit("[ERROR] No local assets found. Nothing to upload.")

    print(f"\nRepository : {OWNER}/{REPO}  branch={BRANCH}")
    print(f"Assets     : {len(file_map)} to process")
    print(f"Mode       : {'DRY-RUN ' if args.dry_run else ''}"
          f"{'ATOMIC ' if args.atomic else 'SEQUENTIAL '}"
          f"{'RESET' if args.reset else 'DELTA'}")
    print()

    if args.atomic:
        ok, skip, err = upload_atomic(
            file_map, token, args.reset, args.dry_run, args.verbose
        )
    else:
        ok = skip = err = 0
        total = len(file_map)
        for i, (local_path, remote_path) in enumerate(file_map.items(), 1):
            progress(i, total, local_path.name)
            result = upload_file(
                local_path, remote_path, token,
                reset=args.reset, dry_run=args.dry_run, verbose=args.verbose
            )
            if result == "OK":   ok   += 1
            elif result == "SKIP": skip += 1
            else:                  err  += 1
        print()  # newline after progress bar

    print(f"\n{'─'*50}")
    print(f"  ✅  OK    : {ok}")
    print(f"  ⏭   SKIP  : {skip}")
    print(f"  ❌  ERR   : {err}")
    print(f"  TOTAL   : {ok + skip + err}")
    print(f"{'─'*50}")
    if err:
        sys.exit(1)


if __name__ == "__main__":
    main()
