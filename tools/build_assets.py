#!/usr/bin/env python3
"""Build the WubiTrainer runtime assets from the two upstream sources.

Inputs (downloaded on first run into tools/sources/, or taken from there if present):

  1. rime-wubi's wubi86.dict.yaml  -- the authentic Wubi86 encoding table.
  2. Jun Da's Modern Chinese character frequency list (list.php?Which=MO)  -- 9,933 chars.
  3. Jun Da's default character frequency list (list.php)                  -- 11,115 chars.

Outputs (written into app/src/main/assets/):

  wubi_chars.tsv     the frequency-ranked practice pool: char, codes, both rankings
  wubi_full.tsv      every single character that has a Wubi86 code (70,944 rows)
  ASSET_MANIFEST.txt provenance: source hashes, row counts, generation date
  ATTRIBUTION.txt    licenses and credits, shown in-app

The script is idempotent and safe to re-run. It deliberately does NOT do any of this
at app runtime: the app only ever reads the generated TSV files.

Usage:
    python3 tools/build_assets.py [--offline]

    --offline   never touch the network; fail if a source file is missing.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import html
import re
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SOURCES = REPO / "tools" / "sources"
ASSETS = REPO / "app" / "src" / "main" / "assets"

WUBI_URL = "https://raw.githubusercontent.com/rime/rime-wubi/master/wubi86.dict.yaml"
FREQ_MODERN_URL = "https://lingua.mtsu.edu/chinese-computing/statistics/char/list.php?Which=MO"
FREQ_OVERALL_URL = "https://lingua.mtsu.edu/chinese-computing/statistics/char/list.php"

# Wubi86 codes are lowercase ASCII letters; 'z' appears only in a handful of
# deprecated radical entries in the source table. Anything else is data corruption.
CODE_RE = re.compile(r"^[a-z]{1,4}$")


def download(url: str, dest: Path, offline: bool) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  cached  {dest.name} ({dest.stat().st_size:,} bytes)")
        return dest
    if offline:
        sys.exit(f"error: {dest} is missing and --offline was given")
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  fetch   {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "wubi-asset-builder/1.0"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = resp.read()
    dest.write_bytes(data)
    print(f"  wrote   {dest.name} ({len(data):,} bytes)")
    return dest


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_wubi(path: Path) -> tuple[dict[str, list[str]], int, int]:
    """Return ({char: [codes...]}, single_char_entries, word_entries).

    The Rime dictionary has a YAML preamble terminated by a line containing only '...'.
    Body lines are `text \\t code \\t weight \\t stem`. Lines beginning with '#' are
    commented-out homophones of the preceding entry and are skipped, exactly as Rime does.
    """
    single: dict[str, set[str]] = defaultdict(set)
    single_entries = 0
    word_entries = 0
    in_body = False
    bad = 0
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not in_body:
                if line.strip() == "...":
                    in_body = True
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                bad += 1
                continue
            text, code = parts[0], parts[1]
            if not CODE_RE.match(code):
                bad += 1
                continue
            # Only single characters are trained (the owner's scope: no words).
            if len(text) != 1:
                word_entries += 1
                continue
            single[text].add(code)
            single_entries += 1
    if bad:
        print(f"  note: skipped {bad} malformed/non-code lines")
    codes = {c: sorted(v, key=lambda x: (len(x), x)) for c, v in single.items()}
    return codes, single_entries, word_entries


def parse_frequency(path: Path) -> list[tuple[int, str, int]]:
    """Parse a Jun Da list.php page into [(rank, char, count)] in rank order.

    The page is GB2312 (decoded as gb18030, a strict superset) and the data lives inside
    a single <pre> block whose rows are separated by <br> and whose fields are tab-separated:
    rank, character, count, cumulative%, pinyin, gloss.
    """
    raw = path.read_bytes().decode("gb18030", errors="replace")
    if "<pre>" not in raw or "</pre>" not in raw:
        sys.exit(f"error: {path} does not look like a Jun Da list page (no <pre> block)")
    block = raw.split("<pre>", 1)[1].split("</pre>", 1)[0]
    rows: list[tuple[int, str, int]] = []
    for chunk in block.split("<br>"):
        chunk = html.unescape(chunk).strip()
        if not chunk:
            continue
        fields = chunk.split("\t")
        if len(fields) < 4:
            continue
        try:
            rank = int(fields[0])
            count = int(float(fields[2]))
        except ValueError:
            continue
        char = fields[1].strip()
        if len(char) != 1:
            continue
        rows.append((rank, char, count))
    rows.sort(key=lambda r: r[0])
    return rows


ATTRIBUTION = """WubiTrainer (五笔练习) — data attribution
==========================================

Wubi86 encoding table
---------------------
rime-wubi / wubi86.dict.yaml
    https://github.com/rime/rime-wubi
    License: GPL-3.0
    Authors credited in the source file: Gong Chen <chen.sst@gmail.com>,
    Yu Yuwei <acevery@gmail.com>, Chen Xing <cxcxcxcx@gmail.com>, and the
    original JidianWubi table author Wozy <wozy.in@gmail.com>.
    Only the single-character entries are used; the dictionary's word entries
    are discarded. Codes are reproduced verbatim.

Character frequency lists
-------------------------
Jun Da (笪骏) — Modern Chinese character frequency list, and the site's default
character frequency list.
    https://lingua.mtsu.edu/chinese-computing/
    Used under the terms stated on that page; the lists are redistributed here
    only as derived frequency ranks inside a derived work.

External explanation pages
--------------------------
The ';' shortcut opens
    https://hantang.github.io/search-wubi/?char=<character>
in the system browser. That page is fetched by the browser under its own terms;
WubiTrainer itself makes no network requests and holds no INTERNET permission.
"""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--offline", action="store_true", help="never touch the network")
    args = ap.parse_args()

    print("=== sources ===")
    wubi_src = download(WUBI_URL, SOURCES / "wubi86.dict.yaml", args.offline)
    modern_src = download(FREQ_MODERN_URL, SOURCES / "freq_modern.html", args.offline)
    overall_src = download(FREQ_OVERALL_URL, SOURCES / "freq_overall.html", args.offline)

    print("=== parse wubi86 ===")
    codes, single_entries, word_entries = parse_wubi(wubi_src)
    print(f"  single-char entries : {single_entries:,}")
    print(f"  word entries (drop) : {word_entries:,}")
    print(f"  distinct characters : {len(codes):,}")

    print("=== parse frequency lists ===")
    modern = parse_frequency(modern_src)
    overall = parse_frequency(overall_src)
    print(f"  modern  : {len(modern):,} characters (rank 1 = {modern[0][1]})")
    print(f"  overall : {len(overall):,} characters (rank 1 = {overall[0][1]})")

    modern_by = {c: (r, n) for r, c, n in modern}
    overall_by = {c: (r, n) for r, c, n in overall}

    # --- wubi_full.tsv: every single character with a code -------------------------
    ASSETS.mkdir(parents=True, exist_ok=True)
    full_path = ASSETS / "wubi_full.tsv"
    with full_path.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("# wubi_full v1\n")
        fh.write("# char\tcodes\n")
        for char in sorted(codes):
            fh.write(f"{char}\t{','.join(codes[char])}\n")
    print(f"=== {full_path.name}: {len(codes):,} rows ===")

    # --- wubi_chars.tsv: the ranked practice pool ---------------------------------
    # The pool is the union of both frequency lists, restricted to characters that
    # actually have a Wubi code (in practice: every one of them), ordered by rank in
    # the frequency list that actually contains them.
    pool: list[tuple[int, int, str]] = []  # (sort_key_a, sort_key_b, char)
    for char in set(modern_by) | set(overall_by):
        if char not in codes:
            continue
        rm = modern_by.get(char, (0, 0))[0]
        ro = overall_by.get(char, (0, 0))[0]
        pool.append((rm if rm else 1 << 30, ro if ro else 1 << 30, char))
    pool.sort(key=lambda t: (t[0], t[1]))

    missing = [c for c in set(modern_by) | set(overall_by) if c not in codes]
    if missing:
        print(f"  note: {len(missing)} frequency-list characters have no Wubi code, "
              f"e.g. {''.join(missing[:10])}")

    chars_path = ASSETS / "wubi_chars.tsv"
    with chars_path.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("# wubi_chars v1\n")
        fh.write("# char\tcodes\trank_modern\tfreq_modern\trank_overall\tfreq_overall\n")
        for _, _, char in pool:
            rm, fm = modern_by.get(char, (0, 0))
            ro, fo = overall_by.get(char, (0, 0))
            fh.write(f"{char}\t{','.join(codes[char])}\t{rm}\t{fm}\t{ro}\t{fo}\n")
    print(f"=== {chars_path.name}: {len(pool):,} rows ===")

    # --- manifest ------------------------------------------------------------------
    manifest = ASSETS / "ASSET_MANIFEST.txt"
    with manifest.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("WubiTrainer asset manifest\n")
        fh.write(f"generated: {_dt.datetime.now().isoformat(timespec='seconds')}\n\n")
        fh.write("sources\n")
        for label, path, url in (
            ("wubi86.dict.yaml", wubi_src, WUBI_URL),
            ("freq_modern.html", modern_src, FREQ_MODERN_URL),
            ("freq_overall.html", overall_src, FREQ_OVERALL_URL),
        ):
            fh.write(f"  {label}\n    url    : {url}\n"
                     f"    bytes  : {path.stat().st_size}\n"
                     f"    sha256 : {sha256(path)}\n")
        fh.write("\nderived\n")
        fh.write(f"  single-character entries : {single_entries}\n")
        fh.write(f"  word entries (discarded) : {word_entries}\n")
        fh.write(f"  wubi_full.tsv rows       : {len(codes)}\n")
        fh.write(f"  wubi_chars.tsv rows      : {len(pool)}\n")
        fh.write(f"  characters with >1 code  : "
                 f"{sum(1 for v in codes.values() if len(v) > 1)}\n")
        fh.write("  codes containing 'z'     : "
                 f"{sum(1 for v in codes.values() for c in v if 'z' in c)}\n")
    print(f"=== {manifest.name} written ===")

    (ASSETS / "ATTRIBUTION.txt").write_text(ATTRIBUTION, encoding="utf-8")
    print("=== ATTRIBUTION.txt written ===")
    print("\ndone.")


if __name__ == "__main__":
    main()
