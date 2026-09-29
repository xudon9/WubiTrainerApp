# 五笔练习 (WubiTrainer) — Product Requirements Document

| | |
|---|---|
| **Product** | 五笔练习 / WubiTrainer — an offline Android drill app for single-character Wubi86 encoding |
| **Package** | `com.xudong.wubitrainer` |
| **Project** | `/home/xudong/AndroidStudioProjects/WubiTrainer` |
| **Author / owner** | 王旭东 (@wangxudong) |
| **Version** | 1.0 (PRD rev. 1) |
| **Date** | 2026-09-29 |
| **Platform** | Android, `minSdk 30`, `compileSdk`/`targetSdk` 37, Kotlin + Jetpack Compose (Material 3) |

---

## 1. Purpose

A learner of Wubi (五笔字型, Wubi86) needs repetition that is (a) *driven by real character
frequency*, (b) *honest about what it has actually taught*, and (c) *usable in short bursts*.
Generic flashcard apps fail this because they show the encoding immediately, do not know Wubi
short codes (简码), and do not distinguish "seen once" from "actually learned".

WubiTrainer is a **single-purpose drill app**: it shows one Chinese character, the learner types
its Wubi code, and the app decides whether that answer *passes* the character. Progress is
persisted to a plain TSV file so it survives crashes and is inspectable with a text editor.

### 1.1 Goals

1. Drill **single characters only** (never multi-character words) using authentic Wubi86 codes.
2. Sample by **Chinese character frequency**, not uniformly.
3. Hide the answer by default; reveal deliberately (`z`) or as *correction* after a mistake.
4. Model "learned" as a **count of correct answers across independent samplings**, not a streak.
5. Keep a **错题集 (mistake set)** that characters fall into on error and leave after K correct
   answers — and let the learner add characters to it by hand.
6. **Never lose progress**: a TSV file written atomically, with a backup copy.
7. **Zero network dependency** at runtime; no permissions required.

### 1.2 Non-goals (v1)

- Training words, phrases, or 词语 codes of length > 4 (the source dictionary's 61,220 word
  entries are deliberately discarded).
- Wubi versions other than 86 (no 98/新世纪/拼音混输).
- Being an input method (writing text into other apps). This is a self-contained drill toy.
- Cloud sync, accounts, leaderboards, gamification beyond local counters.
- Stroke-order animation or radical decomposition diagrams. (We link out instead — see FR-24.)

---

## 2. Data sources and preprocessing

### 2.1 Sources

| Source | Use | Notes |
|---|---|---|
| `https://github.com/rime/rime-wubi/blob/master/wubi86.dict.yaml` | Encodings | Rime dictionary, 2,289,545 bytes, 136,981 lines, UTF-8. Preamble terminated by a line containing only `...`. Body lines are `text \t code \t weight \t stem`, sorted by descending weight. Lines starting with `#` are commented-out homophones and are **skipped**. |
| `https://lingua.mtsu.edu/chinese-computing/statistics/char/list.php?Which=MO` | Frequency | Jun Da's **Modern Chinese** character frequency list. HTML, `<pre>` block, **GB2312/gb18030** encoded. Fields per row: `rank \t char \t count \t cumulative% \t pinyin \t gloss`, rows separated by `<br>`. 9,933 unique characters, corpus 193,504,018 chars. |
| `https://lingua.mtsu.edu/chinese-computing/statistics/char/list.php` (no params) | Alternative frequency | Same site, default view: **11,115** unique characters, corpus 65,348,624 chars — a mixed/literary-weighted corpus (top ranks 之 不 一). Bundled as a **second ranking** so the learner can switch without a rebuild. |

Verified properties of the source data (reconnaissance, 2026-09-29):

- 70,944 distinct single characters carry a Wubi86 code; 61,220 further entries are words (dropped).
- Code length distribution across all single-character entries: 1 letter → 25, 2 → 616, 3 → 7,082, 4 → 67,973.
- 4,445 characters have **more than one** valid code (short codes + full code), e.g. `工` = `a` / `aaa` / `aaaa`.
- 430 characters have **two distinct 4-letter codes** (two valid decompositions), e.g. `齰` = `hbaj` / `hwwj`. So "the" full code is really a *set*.
- 3,405 characters have a **3-letter maximum** code because they decompose into fewer than four
  radicals and have no 4-letter form — `了` = `b` / `bnh`, `有` = `e` / `def`. Therefore
  **"全码" (full code) is defined as the longest code available for that character**, which is
  usually 4 letters but legitimately 3 for these.
- Deprecated/missing-glyph radical entries use `z` (662 codes contain `z`, e.g. `廾` = `zzpp`).
  **Only 6 characters in the 9,933-character frequency pool have a code containing `z`**:
  `匚 钅 肀 攵 尢 彡`. Every other code is drawn from `a`–`y`. This is what makes `z` safe as a
  UI shortcut (see FR-21).
- **All 9,933** Modern-list characters have a Wubi86 code; the intersection is 100%.
- Reach of the Modern ranking (share of real running text covered by the top-N characters):
  **top 1000 → 89.1 %**, 2000 → 97.1 %, **3000 → 99.2 %**, 5000 → 99.9 %, 9933 → 100 %.

### 2.2 Preprocessing (build-time, offline, not in the app)

A `tools/build_assets.py` script (shipped in the repo, run by hand when sources change) downloads
or reads the two sources from `tools/sources/` and emits three assets. The app never parses YAML,
never parses HTML, and never touches the network.

**`app/src/main/assets/wubi_chars.tsv`** — the frequency-ranked practice pool (union of both
frequency lists), sorted by Modern rank:

```
# wubi_chars v1
# char	codes	rank_modern	freq_modern	rank_overall	freq_overall
一	g,ggl,ggll	2	3050722	3	677676
的	r,rqy,rqyy	1	7922684	...
```

- `codes` = comma-separated, **sorted by length ascending**, so the last element is the full code.
- `rank_*`/`freq_*` = `0` when the character is absent from that list.
- **12,041 rows** (the union of both frequency lists, restricted to characters that have a code),
  ≈ 300 KB. The pool is a union rather than just the Modern list so that switching rankings in 设置
  never loses a character.

**`app/src/main/assets/wubi_full.tsv`** — every single character with a Wubi86 code, for manual
additions outside the frequency pool and for a lookup box:

```
# wubi_full v1
# char	codes
齰	hb,hbaj,hwwj
```

- 70,944 rows, ≈ 900 KB. Loaded immediately after the pool but **off the critical path**, so the
  first drill starts as soon as the pool is ready; the 错题集 add box reports 全表载入中 until then.
- 58,903 of its rows are characters that appear in neither frequency list — that is exactly what
  makes a hand-added rare character drillable.

**`app/src/main/assets/ATTRIBUTION.txt`** — verbatim attribution: rime-wubi (GPL-3.0,
Gong Chen / Yu Yuwei / Chen Xing / Wozy), Jun Da's frequency lists
(`https://lingua.mtsu.edu/chinese-computing/`), and the note that `search-wubi` is an external
site opened by an explicit user action. Shown in-app under 设置 → 关于.

### 2.3 Provenance / reproducibility

`tools/build_assets.py` writes `app/src/main/assets/ASSET_MANIFEST.txt` with source SHA-256 sums,
row counts, and the generation date, so a shipped asset can always be traced back to an input.
The script is idempotent and re-runnable.

---

## 3. Data model

### 3.1 In-memory

```kotlin
data class CharEntry(
    val char: String,
    val codes: List<String>,      // length-ascending; codes.last() == full code
    val rankModern: Int, val freqModern: Long,
    val rankOverall: Int, val freqOverall: Long,
) {
    val fullCodes: List<String>   // every code whose length == codes.last().length
    val levelOne: String?         // codes.firstOrNull { it.length == 1 }
}
```

Loaded once into a `WubiRepository` (an application-scoped singleton):

- `poolAll: List<CharEntry>` — sorted by the **active** ranking, ascending rank.
- `byChar: Map<String, CharEntry>` — pool lookup.
- `full: Map<String, List<String>>` — lazily built from `wubi_full.tsv` for lookups of characters
  that are outside the frequency pool.

### 3.2 Persisted: `progress.tsv`

Location: **`filesDir/progress.tsv`** (app-private internal storage; readable via `adb` on a
debuggable build and never requiring a permission). TAB-separated, UTF-8, LF line endings,
comment lines begin with `#`.

```
# wubi-trainer progress v1
# saved_at	1759132800000
# char	correct	wrong	passed	in_mistake	mistake_correct	last_seen_ms
的	2	5	1	0	0	1759132794123
工	1	0	0	1	2	1759132794987
```

| Column | Meaning |
|---|---|
| `char` | the character this row describes |
| `correct` | correct answers accumulated **since the last wrong answer**; resets to 0 on error. Reaching `passCount` ⇒ `passed` (FR-09) |
| `wrong` | lifetime wrong answers for this character |
| `passed` | `1` once `correct` has reached `passCount`; stays `1` afterwards |
| `in_mistake` | `1` while the character is in the 错题集 |
| `mistake_correct` | correct answers accumulated since entering the 错题集; resets to 0 on error. Reaching `mistakeClearCount` ⇒ leaves the set (FR-19) |
| `last_seen_ms` | epoch millis of the last answer, for sorting the 错题集 and for "最近练习" |

Rules:

- A row exists only for characters that have been answered at least once, or manually added to
  the 错题集. Absent row ≡ all-zero.
- Rows for characters not in `wubi_chars.tsv` are **kept** (never silently dropped) — a learner may
  have added a rare character by hand.
- Unknown extra columns are preserved verbatim on rewrite (forward compatibility).
- `# saved_at` is metadata only and may be stale by design (it is rewritten with the file).

### 3.3 Persistence guarantees (crash safety)

1. Every mutation marks the store dirty and schedules a write-behind flush (coalesced, ≥ 400 ms).
2. A flush serialises the whole map to `progress.tsv.tmp`, `flush()`+`fd.sync()`, then
   `rename()` over `progress.tsv` (atomic on the same filesystem). The previous good file is
   copied to `progress.tsv.bak` first.
3. Flushes are also forced on `ON_STOP` of the activity and after every answer **if** the answer
   changed `passed` or `in_mistake` (state transitions are never left to the debounce).
4. Load order: `progress.tsv` → if missing/unparseable → `progress.tsv.bak` → if also bad → start
   empty and surface a non-blocking warning with the path of the quarantined file
   (`progress.tsv.corrupt-<ts>`), which is preserved, never deleted.

### 3.4 Settings

Stored in `SharedPreferences` (`wubi_settings`), **not** in the TSV, so that the progress file
stays a pure progress record. Full reference in §7.

---

## 4. Functional requirements

Requirement IDs are stable and referenced by the code and the test suite.

### 4.1 Practice session core

- **FR-01** The Practice screen shows exactly one target character at a time, large and centred.
- **FR-02** The character's encoding is **hidden by default**. The hidden state shows no letters
  of the answer (only a hint such as 👁 ×N where N is the number of valid codes). It is revealed
  only by FR-06 or FR-20.
- **FR-03** The learner types a code into an input buffer rendered below the character. The buffer
  accepts only `a`–`z` (case-insensitive, lower-cased on entry), is capped at 4 characters, and
  supports backspace and clear.
- **FR-04** **Accept-on-the-fly** (default) — the answer is adjudicated the moment the buffer
  becomes a valid code under the active accept scope (§6.4). The learner does not press Enter.
- **FR-05** **Accept-on-Enter** — the answer is adjudicated only when Enter is pressed. Buffer
  contents that are valid earlier do not advance. This mode is a setting (FR-13) so both
  behaviours are reachable at any time.
- **FR-06** Pressing **`z`** toggles the encoding's visibility while the current character is
  unrevealed (shortcut, FR-21 covers the conflict rule).
- **FR-07** A wrong answer (FR-10) shows a **correction card listing every valid encoding**, with
  each code labelled 一级简码 / 二级简码 / 三级简码 / 全码, and the card stays up until the learner
  acknowledges it (the `Enter` key, or the keypad's `⏎ 下一个` key). Only then is the next character
  sampled. The card carries **no action buttons** while the keypad is on screen — its `；解释` and
  `⏎ 下一个` keys are the same two actions a row below, and putting them on the card as well made
  two identical targets for one job. They reappear only while the keypad is hidden, because then
  nothing else on screen could acknowledge the card.
- **FR-08** After a correct answer the app advances immediately (with a brief non-blocking green
  confirmation), except in the on-Enter mode where Enter itself is the acknowledgement.
- **FR-35 Answer feedback appears in ONE place: a pill on the left of the stats row.** Correct
  answers (`✓ 正确`), characters that pass (`已通过 ✓`), characters that leave the 错题集, and
  **wrong answers** (`✗ 错误`) all announce themselves in the same transient, colour-coded pill,
  which auto-clears after ~1.5 s. Its string is chosen by one `when` in the controller, so no
  outcome can drift into a second location.

  It sits on the **left of the stats row**, not in a band of its own. The stats are right-aligned,
  so that side of the row was empty; and the pill adds no height to the *screen*, because the row it
  lives in is already fixed to the chips' height — the row is chip-height with or without it. (It is
  not that the pill is free: it lives inside a row whose height the chips set, so it grows nothing.)
  That is what a reserved band underneath got wrong. The chips' x positions are unchanged by the
  pill's appearance — measured, they stay at x 575…1022 while the pill occupies x 69…244.

  The type is deliberately oversized (`21 sp`, bold) rather than body text: it is the only
  confirmation an answer landed, and it has to read at a glance while the learner's eyes are down
  on the keypad.

  Before this the same news was split across three places: "✓ 正确" flashed *inside the tally
  line* by the keypad (which also meant the tally vanished on every correct answer), "已通过" went
  to the bottom Snackbar, and errors were silent apart from the correction card. The Snackbar is
  still used for app-level notices that are not answer feedback (adding a character to the 错题集
  by hand, settings confirmations) — those can be raised from the other two tabs, where no stats
  row exists.

  The correction card no longer carries a `❌ 正确编码` heading: the pill is the wrong-answer
  signal, and each encoding in the card already carries its own 一级/二级/三级/全码 label.
- **FR-33** **跳过 (skip)** — move to the next character without answering. A skip touches **no**
  counter: it is neither correct nor wrong, so it must not reset `correct`, must not enter the
  错题集, and must not count as a session answer. The only guard is the engine's own "never the
  same character twice in a row", so a skip is unobservable in `progress.tsv` — verified by
  skipping from a clean install and finding no file written at all.
- **FR-34** **加入错题集 (add to the mistake set by hand)** — records the character on screen as a
  mistake without answering it. It does **not** advance: the learner has just declared this
  character a problem, so the next move is theirs (keep trying, or 跳过). Advancing would be the
  unrecoverable choice, since there is no way to ask for the same character back. Adding a
  character that is already in the set is a no-op and says so.

### 4.2 Passing a character

- **FR-09** A character is **passed** when it has accumulated `passCount` correct answers
  (default **2**, configurable 1–10) in **independent samplings** — i.e. one correct answer per
  presentation of that character, across separate samplings. A single wrong answer at any time
  **before** the character is passed resets `correct` to **0** (the accumulated count is lost, and
  the character must be re-earned from scratch). Once passed, a character stays passed; later
  wrong answers do not un-pass it (they do put it in the 错题集 — FR-18).
- **FR-10** An answer is **wrong** when the learner commits a buffer that is not a valid code
  under the active accept scope, either by pressing Enter (FR-05) or by making the buffer
  impossible (FR-11). A wrong answer shows FR-07 and increments `wrong`.
- **FR-11** **Impossible-prefix detection** (setting, default **on**): if the current buffer is not
  a prefix of *any* code valid under the active scope — e.g. the target is `一` (`g`,`ggl`,`ggll`)
  and the learner types `x` — the answer is judged wrong **immediately**, without waiting for
  Enter. When the setting is off, only Enter adjudicates (the buffer simply cannot succeed).
- **FR-12** The buffer is cleared on every transition to a new character, and on reveal-toggle
  only if the reveal is not itself an answer.

### 4.3 Settings

- **FR-13** A 设置 screen exposes every knob in §7 and persists it immediately. Changing any knob
  takes effect on the **next** sampled character (never mid-answer), except `passCount` /
  `mistakeClearCount`, which take effect on the next adjudication.
- **FR-14** `poolSize` (default **3000**, options 500 / 1000 / 2000 / 3000 / 5000 / 9933 / 全部) selects
  the top-N characters of the active ranking that are eligible for sampling. Characters already
  passed remain visible in stats but leave the sampling pool.
- **FR-15** `freqSource` selects the ranking: **Modern (Which=MO, default)** or **Overall
  (site default)**.
- **FR-16** `weightAlpha` α selects sampling weight shape: `1.0` pure frequency-proportional,
  **`0.5` dampened (default)**, `0.0` uniform over the eligible pool. Weight = `freq^α` (§5).
- **FR-17** 仅全码 (full-code-only) is available **both** as a toolbar quick toggle on the Practice
  screen and as the `acceptScope = FULL_CODE_ONLY` option in 设置. The quick toggle flips the scope
  between the learner's remembered non-strict scope and FULL_CODE_ONLY, and shows an active
  indicator while it is on. When on, **only the longest code** counts as correct, so a 3-letter
  short code for `工` (`a`, `aaa`) is judged wrong while `aaaa` is correct; for `了` the full code
  `bnh` is correct and `b` is wrong. Where a character has two 4-letter codes (`齰` = `hbaj`/`hwwj`)
  **both** are correct.
- **FR-18** 重置进度 (reset progress) with an explicit confirmation dialog offering: reset the
  passed set only / reset the 错题集 only / reset everything. The action writes a
  `progress.tsv.<ts>.bak` copy before wiping, so a mis-tap is recoverable from the file system.

### 4.4 错题集 (mistake set)

- **FR-19** A character **enters** the 错题集 on any wrong answer, and its `mistake_correct`
  counter starts at 0. A character **leaves** the 错题集 in either of two ways:
  1. `mistake_correct` reaches `mistakeClearCount` (default **3**, configurable 1–10) in
     **independent samplings**, using the same semantics as FR-09 — correct answers accumulate, a
     wrong answer resets `mistake_correct` to 0; **or**
  2. **the character passes** (FR-09). Passing is the stronger outcome and discharges the 错题集
     entry immediately, without waiting for K correct answers to accumulate.

  A passed character that is missed again **returns** to the 错题集 (its passing is untouched —
  FR-09 is terminal — but the mistake has to be re-earned out).
- **FR-20** A **错题集 screen** lists every character in the set, most recently seen first, showing
  its character, its codes (visible, since this is a review surface), `wrong`, and
  `mistake_correct` / `mistakeClearCount` as progress. From here the learner can:
  - **manually add** a character via a text field: paste or type one Han character. If it is in
    the frequency pool, its codes are used; if it is only in `wubi_full.tsv`, they are loaded from
    there; if it has **no** Wubi86 code at all, the app refuses with an explanatory message (and
    names the character back).
  - **remove** a character from the set (also clears `mistake_correct`).
  - **start 错题练习** — a session restricted to the mistake set (FR-24).
  - bulk-import several characters at once by pasting a run of Han characters (each is added
    independently; the result reports how many were added and which were rejected).
- **FR-21** Purely-manually-added characters (never answered) show `mistake_correct = 0` and are
  eligible for sampling in 错题模式 immediately.

### 4.5 Sampling

- **FR-22** The next character is drawn from the **eligible pool** = characters in the active
  ranking's top `poolSize` with `passed == false` (in 常规模式) or `in_mistake == true` (in
  错题模式). Ineligible: already-passed characters (regular mode), and characters with no code.
- **FR-23** Selection is a weighted random draw: weight `w_i = freq_i^α` with `freq_i` from the
  active ranking and α = `weightAlpha`. Characters with `freq == 0` (present in only the other
  ranking) get weight `1.0` so they stay reachable rather than impossible. Implementation draws
  one random double and walks a cumulative-weight array — O(log n) with a prefix-sum +
  binary search, no per-sample allocation of a token list.
- **FR-24** **Consecutive samplings never yield the same character**: whenever the eligible pool
  has ≥ 2 members, the previously sampled character is excluded from that draw. If the pool has
  exactly 1 member, it is drawn again — the invariant is stated in terms of what is possible.
- **FR-25** 错题模式 (mistake-set mode) is a distinct session: the streak/accuracy counters reset,
  the header shows 错题练习, and a character leaves the session pool as soon as FR-19 removes it
  from the set. When the set empties, the app celebrates and offers to return to 常规模式.
- **FR-26** When the eligible pool of 常规模式 is empty (everything in the top-N is passed), the
  app shows a completion screen with: the achieved count, a 扩大范围 suggestion (next pool size
  up), and 重置已通过.

### 4.6 Shortcuts

- **FR-27** **`z`** toggles answer visibility (FR-06). Because `z` is a legitimate — if
  deprecated — letter in 662 dictionary codes, the shortcut is **suppressed for the 6 pool
  characters that actually use `z`** (`匚 钅 肀 攵 尢 彡`): for those, and only those, `z` is
  inserted into the buffer as ordinary input. A setting disables the shortcut entirely.
- **FR-28** **`;`** opens the character's explanation page in the system browser at
  `https://hantang.github.io/search-wubi/?char=<percent-encoded character>`. It is available
  **both while typing** (about the current character) and **while the correction card is up**
  (about the character just missed), and is also a `解释` button next to the keypad so it is
  reachable without a hardware keyboard. `;` is never a Wubi letter, so it can never be confused
  with input. A setting disables the shortcut.
- **FR-29** Remaining keyboard bindings on the Practice screen: `Backspace` (⌫) deletes one
  character, `Escape` clears the buffer, `Enter` submits in accept-on-Enter mode and acknowledges
  the correction card in both modes.

### 4.7 Stats and feedback

- **FR-30** The Practice header shows: current mode, session accuracy (correct / total this
  session), passed-count vs pool size (`已通过 128/3000`), 错题集 size, and the current character's
  own tally (`本题 1/2`).
- **FR-31** A live prefix indicator colours the input buffer: neutral while the buffer is a valid
  prefix of some allowed code, **green** when it is exactly an allowed code (in on-Enter mode:
  "ready to submit"), **red** when it cannot lead anywhere.
- **FR-32** A persistent stats line reports lifetime totals: distinct characters practised,
  total answers, accuracy, characters passed, characters in the 错题集. Derived from `progress.tsv`,
  so it is exactly reproducible after a restart.

---

## 5. Answer-flow state machine

```
                 ┌──────────────── SESSION ────────────────┐
                 │                                          │
  sample() ──▶ [PRESENTED] ──type a-z──▶ [BUFFERING] ──┐    │
                 │  ▲                        │          │    │
                 │  │ z / 👁                  │          │    │
                 │  ▼                        │          │    │
              [REVEALED] ──────┐             │          │    │
                 │             │             │          │    │
                 │        ; / 解释 ──▶ browser intent    │    │
                 │                                    │    │
                 │        buffer is allowed code ◀────┘    │
                 │        (on-the-fly) / Enter (on-Enter)  │
                 │                        │                │
                 │             ┌──────────┴──────────┐     │
                 │          CORRECT               WRONG    │
                 │             │                    │      │
                 │  correct++; pass if >= N      correct=0 │
                 │  passing also clears          wrong++   │
                 │    the 错题集 entry           in_mistake=1     │
                 │  else mistakeCorrect++;       mistakeCorrect=0  │
                 │    leave the set if >= K                          │
                 │             │                    │      │
                 │      flash + advance      [CORRECTION CARD]
                 │             │                 (all codes, ; to explain)
                 │             │                    │      │
                 │             └── Enter / 下一个 ───┘      │
                 │                        │                │
                 └──── sample() ◀─────────┴────────────────┘
```

A wrong answer **never** advances on its own: the correction card is a deliberate interruption
(FR-07), which is what makes the mistake informative rather than merely punishing.

---

## 6. Detailed rules

### 6.1 Valid code sets

For a target character with `codes` (length-ascending):

| `acceptScope` | Set of accepted strings |
|---|---|
| `ANY_CODE` (default) | every code in `codes` |
| `EXCLUDE_LEVEL_ONE` | every code except a 1-letter code (so `工`: `aaa`, `aaaa`; `一`: `ggl`, `ggll`) |
| `FULL_CODE_ONLY` | only codes whose length equals `codes.last().length` (all of them, per §2.1) |

The 仅全码 toolbar toggle is a UI affordance over the third row; no separate mechanism exists.

### 6.2 Counters, precisely

| Event | `correct` | `passed` | `mistake_correct` | `in_mistake` | `wrong` |
|---|---|---|---|---|---|
| correct, not in 错题集 | +1 | set when `correct ≥ passCount` | — | — | — |
| correct, in 错题集, character **passes** | +1 | set | **= 0** (the pass clears it outright, without waiting for K) | **= false** | — |
| correct, in 错题集, character does **not** pass | +1 | — | +1; `in_mistake=false` and reset when `≥ mistakeClearCount` | when `≥ K` | — |
| wrong | **= 0** | untouched (once `1`, never cleared) | **= 0** | `= true` (if not already) | +1 |
| manual add to 错题集 | untouched | untouched | = 0 | = true | untouched |
| manual remove from 错题集 | untouched | untouched | = 0 | = false | untouched |

"Correct answers accumulate across independent samplings and a wrong answer resets to zero" is the
literal implementation of the owner's rule. Independence is guaranteed structurally by FR-24:
the same character is never presented twice in a row, so its correct answers always come from
separate samplings.

### 6.3 Prefix classification

For an input buffer `b` and the allowed set `A`:

- `b ∈ A` → **acceptable** (green).
- `∃ a ∈ A`: `a.startsWith(b)` → **viable prefix** (neutral).
- otherwise → **dead** (red; wrong immediately if FR-11 is on).
- empty buffer → neutral, never dead.

### 6.4 Edge cases

| Case | Behaviour |
|---|---|
| Pool has 1 eligible character | It is re-sampled; FR-24's exclusion is waived (documented, not silently broken). |
| Pool has 0 eligible characters | Completion screen (FR-26). |
| 错题模式 with an empty set | Entry point disabled with a hint; the screen offers manual add. |
| Manually added character without a Wubi code (e.g. a Korean syllable) | Refused with a message naming the character. |
| Character with a `z` code | `z` becomes ordinary input, reveal stays available via the 👁 button (FR-27). |
| Multiple 4-letter codes (`齰`) | All are accepted; the card lists them all; either flips `passed`. |
| `passCount` lowered below an existing `correct` | Takes effect at the next adjudication — the character passes on its next correct answer (never retroactively, never silently). |
| Buffer past the longest allowed code length | Backspace/clear; on-Enter mode adjudicates it as wrong on Enter, on-the-fly mode cannot reach it (the buffer becomes dead at the first impossible character, FR-11). |
| App killed mid-answer | Nothing to lose: the answer was not committed. The last committed answer is already on disk (FR-03 guarantees ≤ 400 ms window, and state transitions force an immediate flush). |

---

## 7. Settings reference

| Key | UI label | Default | Options / range | FR |
|---|---|---|---|---|
| `passCount` | 通过所需正确次数 N | **2** | 1–10 | FR-09 |
| `mistakeClearCount` | 错题移出所需正确次数 K | **3** | 1–10 | FR-19 |
| `poolSize` | 练习范围（字频前 N） | **3000** | 500 / 1000 / 2000 / 3000 / 5000 / 9933 / 全部 | FR-14 |
| `freqSource` | 字频表 | **MODERN** | MODERN / OVERALL | FR-15 |
| `weightAlpha` | 抽样权重 | **0.5** | 1.0 纯字频 / 0.5 缓和 / 0.0 均匀 | FR-16 |
| `acceptMode` | 判定方式 | **ON_THE_FLY** | ON_THE_FLY / ON_ENTER | FR-04/05 |
| `acceptScope` | 答案接受范围 | **ANY_CODE** | ANY_CODE / EXCLUDE_LEVEL_ONE / FULL_CODE_ONLY | FR-17 |
| `failOnDeadPrefix` | 不可能的输入立即判错 | **true** | on / off | FR-11 |
| `revealShortcut` | `z` 显示答案快捷键 | **true** | on / off | FR-27 |
| `explainShortcut` | `;` 打开编码解释 | **true** | on / off | FR-28 |
| `showKeypad` | 显示屏幕键盘 | **true** | on / off | §8.4 |
| `keypadHeightDp` | 键盘高度 | **46** | 38 小 / 46 标准 / 56 大 / 68 特大 | §8.1 |
| `glyphFont` | 汉字字体 | **DEFAULT** | 默认 / 宋体 | §8.1 |

---

## 8. UI specification

Single `Activity` (`MainActivity`) hosting Compose, with an app-level state holder. The three
destinations are switched by a **Material 3 `NavigationBar` at the bottom**, inside a Material 3
`Scaffold` that also owns the `TopAppBar` and the `SnackbarHost`. There is no navigation library —
a sealed `Screen` in a `MutableState` is still the whole router — but the *chrome* is Material's,
not hand-rolled:

```
┌──────────────────────────────────────────────────┐
│ [常规] [错题]                        [ 仅全码 ]    │   FilterChips
│                  ⭐1/3000  ❗3   本轮 86%          │   StatChips, right-aligned
├──────────────────────────────────────────────────┤
│                                                  │
│   一         一级简码  g                          │   ~132 sp, anchored LEFT;
│              三级简码  ggl                        │   encodings to its right,
│                 全码  ggll ★                      │   one per line, label then code
│                                                  │
│                  ┌──────────┐                    │
│                  │  gg l    │                    │   input buffer, monospace,
│                  └──────────┘                    │   green border when acceptable
│   本题 1/2  错 0 次       跳过   加入错题集         │   tally + the two session actions
├──────────────────────────────────────────────────┤
│   q  w  e  r  t  y  u  i  o  p                    │
│    a  s  d  f  g  h  j  k  l                      │   on-screen keypad
│     👁Z  x  c  v  b  n  m  ⌫                      │   (z key doubles as reveal)
│     ; 解释    ␣ 清空    ⏎ 下一个/提交             │
├──────────────────────────────────────────────────┤
│   ✏ 练习      ⚠ 错题集 ③      ⚙ 设置               │   NavigationBar (3 tabs)
└──────────────────────────────────────────────────┘
```

Hidden, the encoding slot shows only `👁 3 个编码 / （按 Z 显示）` — never a letter — so the hidden
state leaks nothing and the character's position and size are identical either way.

**Font.** The character's typeface is a setting (默认 / 宋体), rendered through the generic
families Android resolves via its CJK fallback chain. Two things were measured rather than
assumed: on the reference device 默认 and 宋体 differ across 4,439 pixels of the settings sample
and 14,614 pixels of the practice glyph, while a third candidate — `Monospace` — differed across
**zero**, so it was removed rather than shipped as a choice that does nothing. And switching the
font does **not** move the character: its centre stays on the 360 line (1/3) because the slot
centres it by alignment, not by width. The colour is deliberately not a setting — the glyph uses
the theme's `onBackground`, which `ThemeContrastTest` already holds to a readable ratio in both
modes; a free colour choice would have had to re-earn that in every combination.

Two placement decisions are deliberate and were settled with the owner:

1. **The counters live in the header, not the bottom bar.** The bottom bar is navigation; mixing
   read-only statistics into it makes both harder to find.
2. **The keypad sits above the bottom bar, with the input buffer immediately on top of it.** The
   keypad is therefore never on the screen's bottom edge — it clears the gesture area by the height
   of the navigation bar — and the buffer you watch while typing is in the same field of view as
   the keys you touch.

The keypad's key height is a setting (小 / 标准 / 大 / 特大, default 标准 = 46 dp), and the row gap
follows it so a tall keypad does not end up with cramped rows. The character lives in a
`weight(1f)` box, so a taller keypad eats into its room — and the glyph is therefore **capped
against the room it actually has**: 90 % of the box's height, and at most half its width, with a
24 sp floor.

Both caps depend on the **layout only**, never on whether the answer is showing, and that is the
whole point of putting the encodings *beside* the character rather than under it. Two earlier
revisions got this wrong: the first put them below, so revealing the answer added a line to that
strip, shrank the glyph's box, and the character visibly resized and drifted the instant `z` was
pressed; the second kept the character centred, which left the encoding column only 30 % of the
width and visibly crowded. Now the character is anchored **left**, the encodings take everything
left over (~55 % of the content width), and the character's box is `[left edge … glyph width]`
regardless of state — measured, the glyph occupies `[53,536][400,1038]` both hidden and revealed.

The encodings are laid out as a small two-column table, **label first**:

```
  二级简码  qd
  三级简码  qdo
      全码  qdou
```

The label sits in a fixed-width cell and is **right**-aligned in it, so `二级简码` and `全码` share a
right edge; the code sits in a fixed-width cell and is **left**-aligned, so `qd`, `qdo` and `qdou`
share a left edge. Both cells are sized for the widest case (four label characters, four monospace
letters) rather than for the current character's own codes — that costs a little trailing space on
a three-letter code and buys a grid whose x positions never shift as the drill moves from one
character to the next. Because the grid's width is constant, its centre stays on the 2/3 line for
every character (measured: spans 563..878 at 1080 px wide → centre 720).

- **Correction card** (replaces the character area when a wrong answer is pending):
  ```
  ┌── ❌ 一 的正确编码 ────────────────────┐
  │  g     一级简码                          │
  │  ggl   三级简码                          │
  │  ggll  全码  ★                          │
  │                                          │
  │  你输入了 “q”，这已经不可能组成任何编码     │
  └──────────────────────────────────────────┘
     ↑ no buttons: the keypad's  ；解释  and  ⏎ 下一个
       keys do both of these, one row below
  ```
- **Revealed state** replaces the hidden hint with the encodings stacked one per line on the right,
  each carrying its own 一级简码 / 二级简码 / 三级简码 / 全码 label and the full code emphasised. Because
  every line is labelled, the old “加粗为全码 …” footnote is gone.
- Colour is never the only signal: acceptable/dead states also change the border weight and the
  hint line text, for colour-vision safety.

### 8.2 错题集 (mistake set)

Its own top-level destination (no back button — the bottom bar is how you leave).
List (LazyColumn) of `Card` rows `汉字  编码  错 n 次  2/3`; an `OutlinedTextField` at the top accepting a run
of Han characters with a 添加 button; per-row 移除; a bottom 开始错题练习 button (disabled while the
set is empty); a 批量粘贴 affordance. Rejected characters are reported inline.

### 8.3 设置 (settings)

Grouped preference rows matching §7 exactly, plus:
- **关于/数据来源** — source attribution, asset manifest (row counts + hashes), and the live path
  of `progress.tsv` with a 复制路径 button.
- **导出进度** — copies `progress.tsv` to the app's external files dir and offers a share sheet
  (FileProvider), so the learner can back it up or inspect it on a desktop.
- **重置进度** — FR-18.

### 8.4 Input

- A real `BasicTextField`/focus target carries a **hardware key handler** for `a`–`z`, `Enter`,
  `Backspace`, `Escape`, `z`, `;` — this is what makes a desktop/emulator or Bluetooth keyboard
  work, and it is the primary path during development.
- The **on-screen keypad** (QWERTY letter arrangement, matching the physical keys the learner is
  learning) is the primary path on a phone. It guarantees ASCII-free-of-IME input: the app never
  relies on the system keyboard, so a Chinese IME cannot intercept the drill. The system IME is
  therefore never required and the app declares no `windowSoftInputMode` gymnastics.
- The keypad's visibility is a plain setting (default on). It does **not** auto-hide when a hardware
  keyboard is attached: an earlier design did, but the emulator/desktop case then made it
  impossible to reach the keypad at all, and a setting the learner controls is simpler to reason
  about than a heuristic. The keypad's letter keys are disabled while a correction card is up or
  while the pool is exhausted.

---

## 9. Non-functional requirements

| Area | Requirement |
|---|---|
| **Offline** | Zero network calls. No `INTERNET` permission. The only outbound surface is an explicit, user-initiated `ACTION_VIEW` intent for FR-28. |
| **Permissions** | None. |
| **Cold start** | Practice screen interactive < 600 ms warm, < 1.5 s cold, on the reference AVD. Assets are parsed off the main thread; the character appears as soon as the pool is ready. |
| **Memory** | Pool and progress fit in well under 10 MB; the full 70,944-character table is loaded lazily and released when the 错题集 screen leaves. |
| **Durability** | FR-03/§3.3: no answer is ever lost because of a crash, and a corrupt file never destroys data silently. |
| **Correctness** | Unit-tested engine (no Android dependencies in `PracticeEngine` / parsers) so the pass/mistake arithmetic is verifiable without a device. |
| **Accessibility** | Content descriptions on the character, reveal, keypad and correction card; no colour-only signalling; text scales with the system font setting (the big character clamps between 96 sp and 200 sp). |
| **Theming** | Light and dark mode, following the system setting. The app paints its own page (`colorScheme.background`) rather than relying on the window background, publishes `LocalContentColor` so default-coloured text is legible in both modes, and keeps the 正确/错误/提示 semantics as a *theme-aware* palette (dark inks on paper, light tints on near-black). Semantic colours are required to clear **4.5:1 WCAG contrast** against both the page and lifted surfaces — asserted by `ThemeContrastTest`, not by eye. The XML window background has a `values-night` counterpart so the launch frame is never a white flash in dark mode. |
| **Licensing** | GPL-3.0 source dictionary and Jun Da's lists are attributed in-app; the repo carries an `ATTRIBUTION.md`. |
| **Screens** | **Portrait only, locked** (`android:screenOrientation="portrait"`, owner's decision). A drill benefits from the character, the buffer and the keypad never moving, and a second arrangement is a second thing to get wrong. Small windows still shrink the headline and the keys rather than clipping the buffer. |

---

## 10. Test plan / acceptance criteria

### 10.1 Unit tests (JVM, no device)

1. **Dict parsing** — a `一` line yields `g, ggl, ggll`; the word entries are excluded; `#` lines are
   skipped; codes come back length-ascending; `zzpp` for `廾` survives.
2. **Full code** — `了` → `bnh`, `有` → `def`, `齰` → both `hbaj` and `hwwj`.
3. **Frequency parsing** — the gb18030 `<pre>` block round-trips; `的` is rank 1 in Modern; the
   Overall list's rank 1 is `之`; a character missing from one list gets `0` and weight `1.0`.
4. **Passing** — two separate correct answers pass a character with `passCount = 2`; a wrong answer
   between them resets the count and the character is not passed.
5. **Mistake lifecycle** — a wrong answer adds to the 错题集 and zeroes `mistake_correct`; three
   independent corrects remove it; a wrong answer in between restarts the count.
6. **Scope filtering** — with `FULL_CODE_ONLY`, `a` and `aaa` are wrong for `工` and `aaaa` is
   right; with `ANY_CODE` all three are right.
7. **Prefix classification** — for `一`: `` viable, `g` viable, `gg` viable, `ggl` acceptable,
   `ggll` acceptable, `x` dead, `ggx` dead.
8. **Sampling** — 10,000 draws from a two-character pool never repeat consecutively; a single-member
   pool still returns; weights track `freq^α` within a tolerance over 100,000 draws.
9. **Progress round-trip** — write, read, compare; unknown columns preserved; a truncated file
   falls back to `.bak`; a garbage file is quarantined rather than deleted.

### 10.2 Instrumented / manual verification on the emulator (Pixel_7 AVD)

Install the debug APK and confirm, with screenshots as evidence:

1. First launch: a character appears, the encoding is hidden.
2. Type the correct code → advances; the character's tally increments.
3. Type a wrong code + Enter → the correction card lists every code with its level; Enter advances.
4. `z` reveals the encoding while typing; `;` opens the browser at the right `?char=` URL.
5. Type a dead prefix → immediate wrong with the card (setting on), then no card with it off.
6. Toggle 仅全码 → a short code for the same character becomes wrong and the full code right.
7. `passCount = 1` → a single correct answer passes and the character stops appearing.
8. Wrong answers accumulate in the 错题集; 错题练习 drills only those characters; K corrects remove
   one.
9. Manual add of a character outside the pool (from `wubi_full.tsv`) works; adding a
   non-Wubi character is refused with a message.
10. `adb shell am force-stop` mid-session, relaunch → the counters are exactly where they were,
    and `progress.tsv` is valid TSV.
11. Attempt to rotate the device mid-answer → the activity is locked to portrait, nothing
    relayouts, and the half-typed buffer and current character are untouched.

### 10.3 Definition of done

- `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` green from a clean checkout.
- All §10.1 tests pass; all §10.2 scenarios verified on the AVD with screenshots.
- `docs/PRD.md` (this file) reflects what was actually built, with any divergence called out in §12.
- `README.md` documents how to run, where the data came from, and how to rebuild the assets.

---

## 11. Milestones

| # | Milestone | Output |
|---|---|---|
| M1 | Assets pipeline | `tools/build_assets.py`, the three assets, manifest with hashes |
| M2 | Project skeleton | Gradle/AGP/Compose project that builds and launches an empty screen |
| M3 | Engine + persistence | `WubiRepository`, `ProgressStore`, `PracticeEngine` + unit tests green |
| M4 | Practice UI | Practice screen with keypad, shortcuts, correction card, live states |
| M5 | 错题集 + 设置 | Management screen, settings screen, reset/export |
| M6 | Verification | Debug APK installed on the AVD, §10.2 walked through, README |

---

## 12. Decisions, divergences, and open questions

**Decided with the owner (2026-09-29):**

1. Pool default **top 3000** of the frequency ranking, configurable (80 % of responses reported the
   preferred option).
2. Sampling weight **dampened** `freq^0.5` by default; pure and uniform available.
3. Accept-on-the-fly scope is **configurable** (owner's explicit answer). Implemented as the
   three-valued `acceptScope`, with 仅全码 as a quick toggle over it.
4. **Built-in on-screen keypad** plus hardware-keyboard support.
5. 错题 removal uses the **same independent-sampling semantics** as the pass rule.
6. Impossible-prefix immediate-wrong is **configurable, default on**.
7. Name **五笔练习 / WubiTrainer**, package `com.xudong.wubitrainer`.
8. Finishing includes **running the app on the emulator** with evidence.

**Divergences and interpretation calls made by the implementer (flagged for review):**

| # | Call | Why |
|---|---|---|
| D1 | The linked page `list.php` with **no parameters** resolves to a different corpus (11,115 chars, literary-leaning: rank 1 is `之`) than the Modern list `?Which=MO` (9,933 chars, rank 1 is `的`). **Both are bundled**, and the app defaults to **Modern**, switchable in 设置. | The owner's link is the site's default view, but "top 3000 = 99.2 % of modern text" only holds for the Modern list. Bundling both costs ~40 KB and removes the need for a rebuild if the intent was the default view. |
| D2 | "全码" for characters whose longest code is 3 letters (`了` = `bnh`) means that 3-letter code. | 3-letter codes are the longest that exist for those characters; requiring 4 would make them unpassable. |
| D3 | Once passed, a character **stays** passed; a later error does not un-pass it (it does join the 错题集). | "gets it into passed set" is written as a terminal outcome; un-passing would make the passed set unstable and the pool non-monotonic. |
| D4 | A wrong answer to an already-passed character still records `wrong`/错题集, and `correct` is reset. | Keeps the counters honest without reopening the pass decision (D3). |
| D5 | Word entries in `wubi86.dict.yaml` (61,220) are dropped, as instructed. | Owner's explicit scope. |
| D6 | The `z` shortcut is suppressed for the 6 pool characters with `z` codes. | Discovered in reconnaissance (662 `z` codes exist in the dictionary); without the rule those characters would be untrainable via the reveal shortcut. |
| D7 | Progress is TSV; settings are `SharedPreferences`. | The owner asked specifically for a TSV for *progress*; mixing settings into it would make the progress record mutable by unrelated edits. 导出进度 exposes the TSV for inspection. |
| D8 | 错题集 manual entry accepts any Han character found in `wubi_full.tsv` (70,944), not just the frequency pool. | "some specific Hanzi" should not be limited to the top-N drill pool. |
| D9 | The add box refuses non-Han characters, even though the dictionary carries entries for them. | The source table also lists punctuation and Latin-1 symbols (`¤` = `zzhb`, `à` = `zzpy`) with placeholder codes. Nobody learns Wubi for those, and the box is labelled 添加汉字. |
| D10 | **Portrait locked**; there is no landscape layout. | Owner's decision. An earlier revision shipped a side-by-side landscape arrangement; it was removed rather than kept as dead code. |
| D11 | The on-screen keypad does **not** auto-hide on a hardware keyboard; it is a plain on/off setting. | The auto-hide heuristic made the keypad unreachable on the emulator (where `hw.keyboard=yes` is the default), and a learner-controlled switch is simpler than a heuristic. |
| D12 | The practice range is the **union** of both frequency lists (12,041 characters) sliced by the active ranking, not the active list alone. | Otherwise switching 字频表 in 设置 would silently drop characters that had already been practised. |
| D13 | Characters already passed are excluded from sampling; a passed character stays in neither the error nor the completion path. | FR-09's terminal reading of "passed". |
| D14 | Passing clears the 错题集 entry outright, short-circuiting K. | Owner's decision (Q2). K therefore governs only characters that are still *being learned*: once a character has been earned, its mistakes are considered settled. |
| D15 | The semantic answer palette is a `CompositionLocal` (light + dark variants) rather than one fixed set of colours, and the screens paint their own background. | The first revision hard-coded paper-tuned inks and left the background to the XML window theme, so dark mode rendered dark-scheme text on a *light* page: the character was nearly invisible (contrast ~1.1:1 for the glyph) and the error text measured 3.34:1. Verified by screenshot before/after and locked down by `ThemeContrastTest`. |
| D16 | The chrome is **Material 3**: `Scaffold`, `TopAppBar`, `NavigationBar`/`NavigationBarItem` (+`BadgedBox` for the 错题集 count), `SnackbarHost`, `FilterChip`, `Card` with an outlined border, `OutlinedButton`/`FilledTonalButton`, `HorizontalDivider`. | Owner's decision ("The UI should be using material design"). This **supersedes** the earlier stance that the app would avoid `Scaffold`/`TopAppBar` to stay clear of experimental-API opt-ins: `TopAppBar` still needs `@OptIn(ExperimentalMaterial3Api::class)`, and one opt-in is a far smaller cost than a hand-rolled app bar that drifts from the platform. The scheme also gained the `surfaceContainer*`/`inverse*` tokens, because Material components pick their own backgrounds out of those and would otherwise render in baseline-grey that belongs to no theme. |
| D17 | The 错题集 and 设置 buttons moved out of the practice header into a **bottom navigation bar**, and the counters moved back into the header. | Owner's decision. One revision put the counters below the keypad (to lift the keypad off the screen edge); the owner preferred the counters where they were and the *navigation* at the bottom — which lifts the keypad just as well, and puts the two things in the places their roles imply. |

**As-built reconciliation (2026-09-29):** this PRD was written before implementation and then
checked against it. The corrections made: the pool asset holds 12,041 rows (not ~10.2k); the full
table loads eagerly-but-off-the-critical-path rather than lazily on demand; the keypad no longer
auto-hides, and its visibility is a plain setting; the app is portrait-locked and the landscape
layout was removed; D9–D13 above were added. Everything else — the counting rules, the accept
scopes, the sampling contract, the shortcuts, the persistence guarantees — is as specified and is
covered by the tests in §10.1.

**Open questions (non-blocking; current behaviour is a reasonable default):**

- Q1 (→D1) Which frequency list did you intend? Current default: Modern.
- ~~Q2 Should passing a character also *remove* it from the 错题集?~~ **Resolved 2026-09-29: yes.**
  Passing now discharges the 错题集 entry immediately (FR-19, second route). A passed character
  that is missed again rejoins the set.
- Q3 Should the 错题集 keep a separate "how many times I have failed it" history beyond `wrong`?
  Currently `wrong` is the lifetime counter.
- Q4 Do you want an optional daily-goal / streak counter? Out of scope for v1.
