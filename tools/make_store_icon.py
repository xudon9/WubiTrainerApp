#!/usr/bin/env python3
"""Rasterise the adaptive-icon layers into the square store icon the F-Droid metadata wants.

Why this exists: the launcher icon is three vector layers (`mipmap-anydpi-v26/ic_launcher.xml`
plus the drawables `tools/make_icon.py` generates) and there is no raster version anywhere, because
`minSdk` is 30 and every installable device understands adaptive icons. Store metadata is the one
consumer that wants a flat PNG, so this composites the layers and rasterises them.

The conversion is mostly mechanical, because an Android vector path and an SVG path are the same
syntax: `android:pathData` becomes `d` and `android:fillColor` becomes `fill`. The exceptions are
the background's two layers, whose colour comes from an `<aapt:attr name="android:fillColor">`
gradient rather than an attribute, and the foreground's one `<group>` transform.

The framing is deliberate rather than incidental. The launcher composites on a 108x108 canvas and
may mask the outer 18 on every side, so the region guaranteed to be visible is the central 72x72.
The icon uses `viewBox="18 18 72 72"` to show what a learner actually sees instead of the bleed;
without that, the keycap would float in 50% more empty gradient than any launcher would show.

Requires `rsvg-convert` (from librsvg) on PATH — it is not a Python package.

Usage:
    python3 tools/make_store_icon.py [--size 512]

Output (overwritten):
    fastlane/metadata/android/en-US/images/icon.png
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DRAWABLES = REPO / "app" / "src" / "main" / "res" / "drawable"
OUTPUT = REPO / "fastlane" / "metadata" / "android" / "en-US" / "images" / "icon.png"

AAPT = "{http://schemas.android.com/aapt}"
ANDROID = "{http://schemas.android.com/apk/res/android}"

# The central 72 of the 108 canvas; everything outside may be masked away by the launcher.
VISIBLE = (18, 18, 72, 72)


def rgba(value: str) -> tuple[str, float | None]:
    """Android `#RRGGBB` / `#AARRGGBB` -> an SVG colour plus opacity (None when fully opaque)."""
    digits = value.strip().lstrip("#")
    if len(digits) == 8:
        return "#" + digits[2:].upper(), int(digits[0:2], 16) / 255
    if len(digits) == 6:
        return "#" + digits.upper(), None
    raise SystemExit(f"error: unsupported colour {value!r}")


def stops_for(gradient: ET.Element) -> str:
    out = []
    for item in gradient.findall("item"):
        colour, alpha = rgba(item.get(ANDROID + "color"))
        stop = f'<stop offset="{item.get(ANDROID + "offset")}" stop-color="{colour}"'
        if alpha is not None:
            stop += f' stop-opacity="{alpha:.3f}"'
        out.append(stop + "/>")
    return "".join(out)


def gradient_for(node: ET.Element, gid: str) -> str:
    """Expand the `<aapt:attr>` gradient that fills one layer of the background."""
    gradient = node.find(AAPT + "attr").find("gradient")
    kind = gradient.get(ANDROID + "type")
    body = stops_for(gradient)
    if kind == "linear":
        box = " ".join(
            f'{key}="{gradient.get(ANDROID + attr)}"'
            for key, attr in (("x1", "startX"), ("y1", "startY"), ("x2", "endX"), ("y2", "endY"))
        )
        return f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" {box}>{body}</linearGradient>'
    if kind == "radial":
        box = " ".join(
            f'{key}="{gradient.get(ANDROID + attr)}"'
            for key, attr in (("cx", "centerX"), ("cy", "centerY"), ("r", "gradientRadius"))
        )
        return f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" {box}>{body}</radialGradient>'
    raise SystemExit(f"error: unsupported gradient type {kind!r}")


def svg_path(node: ET.Element) -> str | None:
    """One `<path>`, or None for a node that is not a filled path we can translate."""
    data = node.get(ANDROID + "pathData")
    fill = node.get(ANDROID + "fillColor")
    if data is None or fill is None:
        return None
    colour, alpha = rgba(fill)
    opacity = "" if alpha is None else f' fill-opacity="{alpha:.3f}"'
    return f'<path d="{data}" fill="{colour}"{opacity}/>'


def add_layer(drawable: Path, defs: list[str], body: list[str], prefix: str) -> None:
    for node in ET.parse(drawable).getroot():
        tag = node.tag.split("}")[-1]
        if tag == "path":
            if node.find(AAPT + "attr") is not None:
                gid = f"{prefix}{len(defs)}"
                defs.append(gradient_for(node, gid))
                data = node.get(ANDROID + "pathData")
                body.append(f'<path d="{data}" fill="url(#{gid})"/>')
            else:
                drawn = svg_path(node)
                if drawn:
                    body.append(drawn)
        elif tag == "group":
            translate = (node.get(ANDROID + "translateX", "0"), node.get(ANDROID + "translateY", "0"))
            body.append('<g transform="translate({0},{1})">'.format(*translate))
            body.extend(p for p in (svg_path(child) for child in node) if p)
            body.append("</g>")
        else:
            print(f"warning: ignoring <{tag}> in {drawable.name}", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", type=int, default=512, help="edge length in pixels (default 512)")
    args = parser.parse_args()

    rasteriser = shutil.which("rsvg-convert")
    if rasteriser is None:
        raise SystemExit("error: rsvg-convert not found on PATH (install librsvg)")

    defs: list[str] = []
    body: list[str] = []
    add_layer(DRAWABLES / "ic_launcher_background.xml", defs, body, "bg")
    add_layer(DRAWABLES / "ic_launcher_foreground.xml", defs, body, "fg")

    x, y, w, h = VISIBLE
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{args.size}" height="{args.size}" '
        f'viewBox="{x} {y} {w} {h}">\n<defs>\n'
        + "\n".join(defs)
        + "\n</defs>\n"
        + "\n".join(body)
        + "\n</svg>\n"
    )

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "icon.svg"
        source.write_text(svg, encoding="utf-8")
        subprocess.run([rasteriser, "-f", "png", "-o", str(OUTPUT), str(source)], check=True)

    print(f"wrote {OUTPUT.relative_to(REPO)} — {args.size}x{args.size}, "
          f"{len(defs)} gradient(s), {len(body)} element(s)")


if __name__ == "__main__":
    main()
