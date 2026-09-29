# WubiTrainer — Developer & Architecture Guide

English only. This is the document for someone picking up the code cold: what the pieces are, how
they fit, which decisions are load-bearing, and which traps this project actually hit. For the
*product* rules (FR-01…FR-35, the settings reference, the UI specification and the measured pixel
evidence) read [`docs/PRD.md`](PRD.md). For a user-facing overview read [`../README.md`](../README.md).

---

## 1. What it is, and the architecture in one paragraph

WubiTrainer (五笔练习, package `com.xudong.wubitrainer`) is a single-activity Android app: an offline
drill for typing single Chinese characters with Wubi86 codes. There is one `ComponentActivity`
(`MainActivity.kt`) hosting Jetpack Compose (Material 3), one app-level state holder
(`AppController.kt`) that owns everything and exposes it as observable Compose state, a pure rule
engine (`engine/PracticeEngine.kt`) behind a small `ProgressAccess` interface, a data layer backed by
two generated TSV assets and one human-readable `progress.tsv` (written atomically by
`data/ProgressStore.kt`), and settings stored in a `SharedPreferences` file (`wubi_settings`). The
`Activity` declares the relevant `configChanges`, so rotation and keyboard attach/detach do not
recreate it — there is deliberately no `ViewModel` and no process-death restoration story to get
subtly wrong for a file-backed store. Portrait is locked; there are no permissions and no network
calls.

`minSdk` 30, `compileSdk`/`targetSdk` 37, Kotlin + Compose (Material 3). Debug builds get the
`.debug` application-id suffix, so a debug and a release build coexist on one device and keep
separate progress.

---

## 2. Module map and dependency direction

```
app/src/main/java/com/xudong/wubitrainer/
  MainActivity.kt              the only activity (portrait-locked); builds AppController, sets WubiTheme { WubiRoot(app) }
  AppController.kt             app-level state holder: repository + progress + settings + engine + coroutine scope

  data/                        model + persistence (no Compose)
    WubiCode.kt                FreqSource, AcceptScope, AcceptMode, SessionMode, Screen enums; CharEntry
                               (codes length-ascending; codes.last() is the full code) + code helpers
    WubiAssets.kt              pure parsers for the two TSVs + the character-resolution order
    WubiRepository.kt          loads the assets off the main thread (AssetManager); pool + lazy full table

    Progress.kt                CharProgress / ProgressStats / SessionStats + the progress.tsv codec
    ProgressStore.kt           the ProgressAccess impl: atomic write, backup, quarantine, load recovery
    Settings.kt                Settings + SettingsStore (SharedPreferences read/write; no Compose types)

  engine/
    PracticeEngine.kt          the whole rule set — sampling, accept scopes, prefix classification,
                               pass / mistake arithmetic — with NO Android dependency

  ui/                          Compose
    Root.kt                    the shell: Scaffold, TopAppBar, NavigationBar, SnackbarHost, hardware-key routing
    PracticeScreen.kt          drill area, correction card, on-screen keypad
    MistakeScreen.kt           错题集 management (list, manual add, remove, start 错题练习)
    SettingsScreen.kt          settings rows, stats, export, resets, about
    Common.kt                  shared Material-3-based pieces (StatChip, ToggleChip, keycaps, code rows)
    theme/Theme.kt             WubiTheme, the light/dark schemes, the theme-aware PracticeColors palette,
                               GlyphFont, PracticePalette CompositionLocal, CodeTextStyle
```

**Dependency direction:** `ui/` → (`data/`, `engine/`, `AppController`, `ui/theme/`); `AppController`
→ (`data/`, `engine/`); `engine/` → `data/` only; `data/` → JDK + Android framework + coroutines.

**The engine and data layers must not depend on Compose.** The engine is cleanly so; verify both with:

```bash
grep -rn "androidx.compose" \
  app/src/main/java/com/xudong/wubitrainer/engine \
  app/src/main/java/com/xudong/wubitrainer/data
# (no output)
```

`engine/PracticeEngine.kt` imports only `com.xudong.wubitrainer.data.*` plus `kotlin.math` and
`kotlin.random`. There is exactly one caveat to be aware of: `data/Settings.kt` imports
`com.xudong.wubitrainer.ui.theme.GlyphFont` — a plain `enum` (declared in `ui/theme/Theme.kt`) that
carries the 汉字字体 choice. So a `data → ui` *package* edge does exist; it is not a Compose
dependency (the enum imports nothing from Compose), and it is the **only** such edge. If more shared
types start crossing that way, move them down into `data/` rather than widening it.

The practical payoff of the clean engine is that every pass / mistake rule is verified by plain JVM
tests with no device — see §7.

---

## 3. Build, test, install

Everything goes through `scripts/build.sh`, which wires up `JAVA_HOME` (defaulting to
`/opt/android-studio/jbr`) and `ANDROID_SDK_ROOT`, then `exec`s `./gradlew`. Gradle work is slow
(~20 s+ per invocation is normal); that is not a problem, just the cost of a cold daemon.

```bash
cd /home/xudong/AndroidStudioProjects/WubiTrainer

# unit tests + debug APK
./scripts/build.sh :app:testDebugUnitTest :app:assembleDebug --console=plain --no-daemon
# → app/build/outputs/apk/debug/app-debug.apk

# just the tests
./scripts/build.sh :app:testDebugUnitTest --console=plain --no-daemon
```

The debug data files are written under `app/src/main/assets/`, and the test results land as JUnit XML
under `app/build/test-results/testDebugUnitTest/` — read those, not just the `BUILD SUCCESSFUL`
line, when you need the exact count.

### Signed release

```bash
./scripts/build.sh :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Signing material lives at the **repo root**:

- `keystore.properties` — a `Properties` file with `storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`. It is `chmod 600`.
- `wubitrainer-release.jks` — the keystore itself.

Both are **gitignored** (see `.gitignore`). `app/build.gradle.kts` reads them defensively:
`hasSigningConfig` is true only when `keystore.properties` exists and has a `storeFile`, and the
`release` signing config is created conditionally — so a fresh clone (or CI) still builds, it just
produces an **unsigned** release APK.

**Never print, copy, commit or paste the contents of `keystore.properties` or the `.jks`.** Losing
the keystore means you can never update an installed copy of the app, because Android refuses an
update signed with a different key. Back both files up somewhere other than this machine.

Check the signed result — note that `apksigner` needs `java` on `PATH`:

```bash
$JAVA_HOME/bin/java -version >/dev/null
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose \
  app/build/outputs/apk/release/app-release.apk
```

AGP 9 signs with the **highest enabled scheme only**; `enableV3Signing = true` is the only scheme set
in `app/build.gradle.kts`. So the release APK correctly reports `v3: true` with `v1: false, v2: false`
— that is expected, not broken (see §8).

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk   # package com.xudong.wubitrainer.debug
# release installs as                                      com.xudong.wubitrainer
```

Read progress back off the device with:

```bash
adb shell run-as com.xudong.wubitrainer.debug cat files/progress.tsv
```

or use **设置 → 导出进度 TSV** (a FileProvider share sheet; see `res/xml/file_paths.xml`).

Opening the project in Android Studio also works as usual — `local.properties` already points at
`/home/xudong/Android/Sdk`.

---

## 4. The asset pipeline (`tools/build_assets.py`)

`tools/build_assets.py` (`python3 tools/build_assets.py [--offline]`) downloads or reuses three
upstream inputs from `tools/sources/`, and writes four files into `app/src/main/assets/`. The app
never parses YAML or HTML and never touches the network — it only reads the generated TSVs.

Inputs:

1. `rime-wubi`'s `wubi86.dict.yaml` — the authentic Wubi86 encoding table (YAML preamble terminated
   by a line containing only `...`; body lines are `text \t code \t weight \t stem`; `#` lines are
   commented-out homophones and are skipped).
2. Jun Da's Modern Chinese frequency list (`list.php?Which=MO`) — 9,933 characters, GB2312/gb18030,
   data inside one `<pre>` block, rows separated by `<br>`.
3. Jun Da's default frequency list (`list.php`) — 11,115 characters.

Outputs:

| File | Rows | Row format |
|---|---|---|
| `wubi_chars.tsv` | 12,041 | `char \t codes \t rank_modern \t freq_modern \t rank_overall \t freq_overall` |
| `wubi_full.tsv` | 70,944 | `char \t codes` |
| `ASSET_MANIFEST.txt` | — | source URLs, byte sizes, SHA-256 sums, row counts, generation date |
| `ATTRIBUTION.txt` | — | licences and credits (also shown in-app under 设置 → 关于) |

What each row means: `char` is a single character; `codes` is the comma-separated list of its valid
Wubi86 codes, **sorted by length ascending** so the last element is the full code (`一` →
`g,ggl,ggll`); the `rank_*`/`freq_*` pairs are the character's position and raw count in each
frequency list, and are `0` when the character is absent from that list.

Two pipeline decisions matter:

- **Only single characters are kept.** A line whose text is longer than one character is a word
  entry and is counted and **discarded** (61,220 of them). This app trains characters, not words
  (PRD D5). Codes are validated against `^[a-z]{1,4}$`; anything else is skipped as corruption.
- **The practice pool is the union of both frequency lists**, restricted to characters that have a
  Wubi code and ordered by rank in whichever list contains them (absent from a list → a
  `1 << 30` sentinel rank). This is why `wubi_chars.tsv` is 12,041 rows rather than just the 9,933
  of the Modern list: switching the 字频表 in 设置 must never silently drop a character the learner
  has already practised (PRD D12).

`wubi_full.tsv` (70,944 rows, ≈ 900 KB) exists so a character that appears in *neither* frequency
list can still be added to the 错题集 by hand; 58,903 of its rows are such characters. It is loaded
straight after the pool but **off the critical path** (`WubiRepository.loadFullTable()` on the IO
dispatcher), so the first drill starts as soon as the pool is ready.

Regenerate with:

```bash
python3 tools/build_assets.py            # downloads into tools/sources/ on first run
python3 tools/build_assets.py --offline  # reuse the cached sources; fail if any is missing
```

The script is idempotent and re-runnable; `ASSET_MANIFEST.txt` is the provenance record that ties a
shipped asset back to its inputs.

---

## 5. The icon generator (`tools/make_icon.py`)

`python3 tools/make_icon.py` (no arguments needed) traces the **五** glyph outline out of a real CJK
font — Noto Sans CJK (`/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc` by default) — using
`fontTools`, scales it into the adaptive-icon 108×108 viewport (Y flipped from font space to SVG
space), and writes vector drawables.

Why trace a font rather than draw the glyph by hand: the first version of this icon drew 五 as four
hand-placed strokes, and at 48 dp they landed on the key boundaries, so the character read as a grid
rather than as 五. Tracing the outline removes the guesswork — the glyph *is* the character, by
construction.

The adaptive icon is **three separate drawables**, because that is what the launcher machinery wants
(it masks any shape out of them and tints the monochrome one for Android 13+ themed icons):

| Layer | File | Produced by |
|---|---|---|
| background | `app/src/main/res/drawable/ic_launcher_background.xml` | hand-authored (teal gradient, full bleed) |
| foreground | `app/src/main/res/drawable/ic_launcher_foreground.xml` | `tools/make_icon.py` |
| monochrome | `app/src/main/res/drawable/ic_launcher_monochrome.xml` | `tools/make_icon.py` |

The foreground is a light key-cap frame holding four blue keys, a small orange indicator dot, and 五
in white across it; the monochrome layer is *just* 五 (the frame, keys and dot would flatten into an
unreadable blob once themed). The two generated files carry a header comment saying they are
generated and how. Because `minSdk` is 30, every installable device supports adaptive icons, so
there are no per-density PNG fallbacks — the layers are referenced from
`res/mipmap-anydpi-v26/ic_launcher.xml`.

Run it with no arguments to regenerate; pass `--font /path/to/font.ttc` or `--char 字` to override
the defaults.

---

## 6. Design invariants that must not be broken

Each of these is load-bearing: it exists because something broke without it. Several carry measured
pixel evidence in `docs/PRD.md` §8.

1. **The drill area is a rule-of-thirds layout.** The character's centre sits on the **1/3** line and
   the encoding table's centre on the **2/3** line, and both are stable across a reveal. In
   `ui/PracticeScreen.kt` this is `THIRDS_SLOT_FRACTION = 2f / 3f`: the glyph slot is
   `align(Alignment.CenterStart).fillMaxWidth(2f/3f)` and the answer slot is
   `align(Alignment.CenterEnd).fillMaxWidth(2f/3f)`. A container centred within a two-thirds span has
   its centre exactly one third in from that span's far edge. Measured: the character's centre stays
   on the **x = 360** line (1/3) and the table's centre on **x = 720** (2/3) at 1080 px wide. Both
   slots therefore overlap in the middle third, which is harmless because the glyph is capped away
   from the table. Do **not** add horizontal padding to the drill area — the slots are sized as
   fractions of the full screen, not of an inset box.

2. **The glyph's size is capped from LAYOUT only — never from whether the answer is showing.** The
   cap is computed once from `BoxWithConstraints`' `maxHeight` and `maxWidth`
   (`heightCapSp = maxHeight.toSp().value * 0.9f`, `widthCapSp` from `maxWidth * 2/3 − 2 ×
   GLYPH_SIDE_RESERVE`), then clamped in `CharacterGlyph`
   (`.coerceIn(minSize, 210f).coerceAtMost(capSp).coerceAtLeast(24f)`). Because nothing in that chain
   reads `app.revealed`, pressing **`z`** cannot resize or move the character. Two earlier revisions
   got this wrong: one put the encodings *below* the character, so revealing them added a line,
   shrank the glyph's box and made the character visibly resize and drift the instant `z` was
   pressed. Measured after the fix: the glyph occupies `[53,536][400,1038]` both hidden and revealed.

3. **The stats row's height comes from the permanent chips, never from the transient answer pill.**
   In `ui/PracticeScreen.kt` the row is `Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min))`.
   `IntrinsicSize.Min` sizes it to the tallest *minimum intrinsic height* among the children, and the
   chips are the taller, permanent element. The chips themselves use `maxLines = 1, softWrap = false`
   (`ui/Common.kt`, `StatChip`) so a squeezed label ellipsizes instead of wrapping — a wrapping label
   grew the chip, which grew the row, which moved the divider below it. An earlier attempt hard-coded
   the row to the *pill's* height, which was shorter than the chips and clipped their bottom line.

4. **The pill sits in a `weight(1f)` slot so it yields width rather than squeezing the chips.** The
   pill is a `Box(Modifier.weight(1f).fillMaxHeight())` to the left of the `StatChip`s. Chips are
   measured first at their natural width; the pill gets only what is left over. The other way round —
   pill first, then a weighted spacer — let the pill squeeze the chips, and `本轮正确率` wrapped onto
   two lines, growing the chip, the row, and the divider below it. Measured: the chips' x positions
   are unchanged by the pill's appearance (x 575…1022 while the pill occupies x 69…244).

5. **The semantic colour palette is theme-aware (light + dark), and `ThemeContrastTest` holds every
   one of them to a WCAG ratio.** `ui/theme/Theme.kt` defines `LightPracticeColors` /
   `DarkPracticeColors` (exposed via the `PracticePalette` `staticCompositionLocalOf`) with the four
   semantic colours `correct`, `wrong`, `caution`, `neutral`. `ThemeContrastTest` asserts each of them
   clears **4.5:1** against *both* the page background and a lifted surface, in *both* modes. Colour
   is never the only signal — each state is paired with a glyph or a word. **A new semantic colour
   must be added to the palette and to `ThemeContrastTest.checkMode`'s list**, or it is unguarded.
   The dark theme originally shipped one fixed paper-tuned set, which rendered unreadable on the dark
   page (glyph contrast ≈ 1.1:1); that is the regression the test exists to catch forever.

6. **The engine refuses to return the same character twice in a row.** That is how "N correct answers
   from independent samplings" is implemented without a streak counter. `PracticeEngine` keeps
   `lastSampled` and `sample(mode, avoidChar = lastSampled)` excludes it from the draw whenever the
   eligible pool has ≥ 2 members (a single-member pool is re-sampled; the invariant is stated in
   terms of what is possible). The owner's rule "N times, NOT N in a row" is therefore enforced
   *structurally* by sampling, not by counting. The `avoidChar` parameter exists only so a test can
   force the branch.

7. **The 错题集 "add by hand" path and the practice pool both come from the union of the two
   frequency lists**, so switching 字频表 can never silently drop a practised character. The pool
   asset is already the union (12,041 rows); `WubiRepository.loadPool()` sorts that one parsed list
   into `poolModern` and `poolOverall`, and `PracticeEngine.eligible()` slices whichever the active
   `FreqSource` names. Hand-added characters and 错题模式 both resolve through
   `WubiRepository.lookup()`, which consults the pool **and** the full 70,944-character table, so a
   rare character added by hand is drillable even though it is in neither frequency list.

---

## 7. Testing

58 JVM unit tests, in `app/src/test/java/com/xudong/wubitrainer/`:

| File | Tests | Covers |
|---|---|---|
| `PracticeEngineTest.kt` | 35 | The whole counting rule set: N independent corrects pass a character; a wrong answer resets the count; passing is terminal; the 错题集 lifecycle (enter on a miss, leave after K independent corrects or immediately on a pass); accept scopes (`ANY_CODE` / `EXCLUDE_LEVEL_ONE` / `FULL_CODE_ONLY`, including two-letter full codes and two 4-letter codes); prefix classification; sampling (no consecutive repeats over 10,000 draws, single-member pool, weights track `freq^α`, mistake-mode scoping); stats aggregation and the reset helpers. |
| `ProgressStoreTest.kt` | 7 | The `progress.tsv` codec and crash recovery: round-trip of every column; unknown future columns preserved verbatim; damaged rows reported not invented; a corrupt main file falls back to `.bak` and quarantines the original; both-unusable starts empty without destroying anything; an absent file is not an error. |
| `WubiAssetsTest.kt` | 10 | The pure parsers **plus an integrity check against the real generated assets** (`src/main/assets/*.tsv`, which is what unit tests run against): codes come back length-ascending; `z` codes survive; malformed rows are skipped; the shipped pool and full table still have the documented shape; hand-added characters resolve from the full table only when they have a code; only Han characters are addable; the manifest and attribution ship with the app. |
| `ThemeContrastTest.kt` | 6 | WCAG 2.1 contrast: every semantic colour readable in light and dark, the dark palette genuinely a light-on-dark inversion, page/surface text readable in both modes, surfaces distinguishable from the page, and the contrast maths itself. |

Run them with:

```bash
./scripts/build.sh :app:testDebugUnitTest --console=plain --no-daemon
```

**Project habit: make a test fail on purpose before trusting it.** A green test that has never been
seen to go red proves nothing — it may be asserting nothing at all. When adding or changing a test,
break the thing it guards, watch it fail for the right reason, then fix the thing and watch it pass.
Treat the exact count as a signal too: read the JUnit XML under
`app/build/test-results/testDebugUnitTest/` rather than trusting the summary line, so a test that
silently stopped being discovered is caught.

---

## 8. Pitfalls this project actually hit

These cost real time; they are recorded here so they cost it once.

- **`apksigner` needs `java` on `PATH`, and AGP 9 signs with the HIGHEST enabled scheme only.** A
  release APK reporting `v1: false, v2: false` is *correct*, not broken: `app/build.gradle.kts` sets
  `enableV3Signing = true` and nothing else, and AGP 9 emits only that scheme. v3 has existed since
  Android 9 and `minSdk` is 30, so every device this APK can install on reads it. Enabling v2 as well
  has no effect (measured). If `apksigner verify` errors out entirely rather than reporting schemes,
  the cause is usually that `java` is not on `PATH` — prefix it with `$JAVA_HOME/bin/java -version
  >/dev/null` as in §3.

- **Transient UI is hard to observe: `uiautomator dump` takes longer than a 1.5 s toast.** The answer
  pill (FR-35) auto-clears after ~1.5 s (`AppController.AnswerFlash` + `delay(1500)` in
  `PracticeHeader`), so a UI dump started after the fact sees an empty screen and "proves" the pill
  never appeared. To capture transient elements, take a **`screencap`** at the moment they are on
  screen — or lengthen the delay temporarily — rather than trusting a dump.

- **Before believing a search found nothing, make it match something you know is present.** An empty
  `grep`/`find` result is ambiguous: the pattern may be wrong, the path may be wrong, or the tool may
  be silently doing nothing. Confirm the search *can* return a hit on a known-present string first,
  then trust its emptiness. (This is exactly how the one unreferenced resource in this repo —
  `res/mipmap-anydpi-v26/ic_launcher_round.xml`, which nothing references because the manifest
  declares no `android:roundIcon` — was confirmed before removal.)

- **Kotlin gotchas that bit this codebase:** `2 * someDp` does **not** compile — Kotlin defines
  `Dp * Int` but not `Int * Dp`, so write `someDp * 2`. And a composable's action/`onClick` parameter
  **must be last**, or a trailing lambda binds to the wrong parameter — e.g.
  `ToggleChip("常规", app.mode == SessionMode.REGULAR) { app.switchMode(...) }` only works because the
  handler is the last parameter.

- **Android has no CJK monospace face, so a `Monospace` font option renders identically to the
  default.** It was shipped as a 汉字字体 choice, measured across **zero** differing pixels against
  the default, and removed rather than kept as a choice that does nothing. (The surviving options —
  默认 / 宋体 — were measured to differ across **4,439** pixels of the settings sample and 14,614
  pixels of the practice glyph, so they are real.)

- **Android has no first-class CJK typeface API.** The 汉字字体 options go through the *generic*
  families (`FontFamily.Default`, `FontFamily.Serif`) and the platform's CJK fallback chain decides
  the actual face — so what 宋体 renders depends on the device's own fonts. That is why the settings
  screen shows a **live sample** instead of asking the learner to trust a label.

---

## 9. Docs index — which document is authoritative for what

| Document | Scope | Authoritative for |
|---|---|---|
| [`docs/PRD.md`](PRD.md) | Product requirements + decisions | FR-01…FR-35, the settings reference (§7), the UI specification and layout diagrams (§8), the decision records D1…D17, and the **measured pixel evidence** (the numbers quoted in §6 above come from here, e.g. 4,439 pixels, the 1/3–2/3 lines at x = 360 / x = 720). If a product rule and this file disagree, the PRD wins. |
| [`README.md`](../README.md) | User-facing, bilingual (中文 first, then English) | What the app is, the feature list, the settings table, the screens, build / install / release commands, testing, and data attribution. Written for someone who wants to *use* or *build* it. |
| `docs/DEVELOPER.md` (this file) | Developer / architecture | The module map and dependency direction, the asset and icon pipelines, the design invariants, the test layout, and the pitfalls. Written for someone who wants to *change* it. |

`docs/PRD.md` is the source of record for product behaviour; this file describes how that behaviour
is built and which parts must not be disturbed.
