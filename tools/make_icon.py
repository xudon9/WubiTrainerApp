#!/usr/bin/env python3
"""Generate the launcher icon layers from a real CJK font.

Why this exists: the first version of this icon drew 五 by hand as four strokes. At 48 dp the
strokes landed on the key boundaries and the character read as a grid, not as 五. Tracing the
outline from Noto Sans CJK Bold removes the guesswork — the glyph is the character, by
construction.

The three layers are independent drawables because that is what an adaptive icon wants: the
launcher masks any shape out of them and tints the monochrome one for themed icons.

Usage:
    python3 tools/make_icon.py [--font /path/to/a/CJK/font.ttc] [--char 五]

Outputs (overwritten):
    app/src/main/res/drawable/ic_launcher_foreground.xml
    app/src/main/res/drawable/ic_launcher_monochrome.xml
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection, TTFont

REPO = Path(__file__).resolve().parent.parent
DRAWABLES = REPO / "app" / "src" / "main" / "res" / "drawable"

DEFAULT_FONTS = (
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
)

# Canvas is 108x108. Only the central ~72x72 is guaranteed visible; the rest is bleed that a
# launcher mask may cut away, so everything meaningful stays well inside it.
GLYPH_BOX = (34.0, 41.0, 74.0, 81.0)      # 五 over the keys, small enough to leave them visible
MONO_BOX = (14.0, 14.0, 94.0, 94.0)       # 五 alone fills more of the canvas

# Four keys, 2x2, alternating two blues so they read as separate keys rather than a grid.
SQUARES = [(28, 28, "#5CC4E8"), (55, 28, "#4BB2DC"), (28, 55, "#4BB2DC"), (55, 55, "#5CC4E8")]
SQUARE_SIZE = 25


def load_font(path: str, char: str):
    """First face that actually contains `char` — a .ttc holds several language variants."""
    obj = TTCollection(path) if path.endswith(".ttc") else TTFont(path)
    faces = getattr(obj, "fonts", [obj])
    code = ord(char)
    for face in faces:
        if code in face.getBestCmap():
            return face
    raise SystemExit(f"error: no face in {path} contains {char!r}")


def glyph_path(font, char: str, box: tuple[float, float, float, float], decimals: int = 2) -> str:
    """Outline of `char`, mapped into `box` with Y flipped from font space to SVG space."""
    glyph_set = font.getGlyphSet()
    name = font.getBestCmap()[ord(char)]

    bounds = BoundsPen(glyph_set)
    glyph_set[name].draw(bounds)
    x_min, y_min, x_max, y_max = bounds.bounds
    w, h = x_max - x_min, y_max - y_min

    x0, y0, x1, y1 = box
    scale = min((x1 - x0) / w, (y1 - y0) / h)
    tx = x0 + ((x1 - x0) - w * scale) / 2
    ty = y0 + ((y1 - y0) - h * scale) / 2
    transform = Transform(scale, 0, 0, -scale, tx - x_min * scale, ty + y_max * scale)

    pen = SVGPathPen(glyph_set)
    glyph_set[name].draw(TransformPen(pen, transform))
    # Trim coordinate precision so the generated XML stays readable and diffable.
    return re.sub(
        r"(-?\d+\.\d+)",
        lambda m: f"{float(m.group(1)):.{decimals}f}".rstrip("0").rstrip("."),
        pen.getCommands(),
    )


def rounded_square(x: int, y: int, size: int, r: int = 4) -> str:
    return (
        f"M{x + r},{y} L{x + size - r},{y} A{r},{r} 0 0 1 {x + size},{y + r} "
        f"L{x + size},{y + size - r} A{r},{r} 0 0 1 {x + size - r},{y + size} "
        f"L{x + r},{y + size} A{r},{r} 0 0 1 {x},{y + size - r} "
        f"L{x},{y + r} A{r},{r} 0 0 1 {x + r},{y} Z"
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", default=None, help="a CJK font (.ttc/.otf/.ttf)")
    ap.add_argument("--char", default="五")
    args = ap.parse_args()

    font_path = args.font
    if font_path is None:
        font_path = next((p for p in DEFAULT_FONTS if Path(p).is_file()), None)
        if font_path is None:
            raise SystemExit("error: no default CJK font found; pass --font")
    font = load_font(font_path, args.char)
    print(f"font : {font_path}  ({font['name'].getDebugName(4)})")
    print(f"glyph: {args.char}  U+{ord(args.char):04X}")

    glyph = glyph_path(font, args.char, GLYPH_BOX)
    mono = glyph_path(font, args.char, MONO_BOX)

    keys = "\n".join(
        f'    <path android:pathData="{rounded_square(x, y, SQUARE_SIZE)}" '
        f'android:fillColor="{colour}" />'
        for x, y, colour in SQUARES
    )

    DRAWABLES.mkdir(parents=True, exist_ok=True)
    (DRAWABLES / "ic_launcher_foreground.xml").write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  Adaptive icon — foreground layer.

  A light key-cap frame holding four blue keys, a small orange indicator dot, and {args.char} in
  white across them.

  {args.char} is not hand-drawn. Traced from {Path(font_path).name}, so it is the character rather
  than an approximation of it. Regenerate with: python3 tools/make_icon.py
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Frame, then the recessed panel the keys sit in. -->
    <path
        android:pathData="M33,24 L75,24 A9,9 0 0 1 84,33 L84,75 A9,9 0 0 1 75,84 L33,84 A9,9 0 0 1 24,75 L24,33 A9,9 0 0 1 33,24 Z"
        android:fillColor="#A8E7EE" />
    <path
        android:pathData="M36,29 L72,29 A6,6 0 0 1 78,35 L78,73 A6,6 0 0 1 72,79 L36,79 A6,6 0 0 1 30,73 L30,35 A6,6 0 0 1 36,29 Z"
        android:fillColor="#256E86" />

    <!-- Four blue keys. -->
{keys}

    <!-- Indicator, with a halo so it still reads at 48 dp. -->
    <path android:pathData="M71,36 m-6.5,0 a6.5,6.5 0 1,0 13,0 a6.5,6.5 0 1,0 -13,0" android:fillColor="#40FF8C1A" />
    <path android:pathData="M71,36 m-3.4,0 a3.4,3.4 0 1,0 6.8,0 a3.4,3.4 0 1,0 -6.8,0" android:fillColor="#FFFF8C1A" />

    <!-- {args.char}: a shadow pass, then the white pass on top. -->
    <group android:translateY="1.5">
        <path android:pathData="{glyph}" android:fillColor="#590A3A48" />
    </group>
    <path android:pathData="{glyph}" android:fillColor="#FFFFFFFF" />
</vector>
""",
        encoding="utf-8",
    )

    (DRAWABLES / "ic_launcher_monochrome.xml").write_text(
        f"""<?xml version="1.0" encoding="utf-8"?>
<!--
  Adaptive icon — monochrome layer (Android 13+ themed icons).

  Just {args.char}. The frame, keys and dot are decoration that the launcher would flatten into an
  unreadable blob once it tinted the layer.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="{mono}" android:fillColor="#FFFFFFFF" />
</vector>
""",
        encoding="utf-8",
    )
    print(f"wrote {DRAWABLES / 'ic_launcher_foreground.xml'}")
    print(f"wrote {DRAWABLES / 'ic_launcher_monochrome.xml'}")


if __name__ == "__main__":
    main()
