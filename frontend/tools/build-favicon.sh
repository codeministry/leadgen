#!/usr/bin/env bash
# Renders the favicon set and the install icons from the mark.
#
# The source is `brand/mark.svg`, the one drawing of the lead ring, and the same paths
# `shared/brand-mark/` inlines in the header. Two groups carry the two colours: `body`
# is the ring, `signal` the two dots. The script recolours by group id, so nothing here
# knows a pixel box; a redrawn mark with the same two ids renders without a change.
#
# The plate is the dark theme's surface and the mark takes the dark theme's colours —
# lavender ring, magenta dots — because a tab icon has no theme and the dark theme is
# the default (spec 003, 2026-09-24). The signal is the same hue in both themes, so the
# dots read as the brand's accent wherever the tab is.
#
# Two plates, one per purpose (ISC-328). The round plate, with transparent corners, is
# the tab icon and the manifest's `any` icons: the mark sits on the inscribed square,
# 176 of 256, which leaves the ring's open end clear of the curve. The square plate is
# opaque and full-bleed: Android masks a `maskable` icon into its own shape and iOS rounds
# a touch icon itself, and either paints white behind a transparent corner.
#
# On the square plate the mark has to stay inside the safe zone, the central circle of
# 80 % of the width that Chrome and Android mask against. The mark's farthest point is not
# the ring but the outer signal dot: at cx=27.5 cy=6.5 r=3 in the 32-box its far edge is
# sqrt(11.5² + 9.5²) + 3 = 17.92 units from the centre, 1.12 times the half-box. So the mark
# may be at most 0.4 / (1.12 / 2) = 0.714 of the size, and the round plate's 176/256 = 0.6875
# is reused: 352 of 512 puts the dot's far edge 197 px from the centre against a safe radius
# of 204.8. At 80 % the mask took 24 px off that dot, which `manifest.spec.ts` now measures.
#
# Needs `rsvg-convert` (librsvg) and ImageMagick 7 (`magick`). Run from `frontend/`.
# Output is deterministic: no timestamps are written, so running it twice leaves
# `git diff` empty (ISC-239, ISC-328). The bytes do depend on the renderers, though;
# the committed PNGs were rendered with rsvg-convert 2.63.2 on cairo 1.18.4 and
# ImageMagick 7.1.2-31 Q16-HDRI, and another version may anti-alias an edge differently.

set -euo pipefail

src=brand/mark.svg
ring='#A3B2FE'    # lg-dark --color-primary
signal='#FB80CA'  # lg-dark --color-accent, the signal
plate='#1F222D'   # lg-dark --color-base-100

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Recolour by group: every `#000` inside `<g id="body">` becomes the ring colour, every
# one inside `<g id="signal">` the signal. The groups are sequential in the file, which
# is what lets a line-oriented tool do it without an XML parser.
awk -v ring="$ring" -v signal="$signal" '
  /<g id="body">/   { colour = ring }
  /<g id="signal">/ { colour = signal }
  /<\/g>/           { colour = "" }
  { if (colour != "") gsub(/#000/, colour); print }
' "$src" > "$work/mark-coloured.svg"

# The mark rendered once per pixel width, from the SVG rather than by resizing a larger
# render, so every size gets its own anti-aliasing.
mark() { # width
  local width=$1
  [[ -f "$work/mark-$width.png" ]] ||
    rsvg-convert --width "$width" --height "$width" "$work/mark-coloured.svg" > "$work/mark-$width.png"
  printf '%s' "$work/mark-$width.png"
}

# The round plate: a circle of the full size on a transparent canvas, the mark on the
# inscribed square at 176/256 of the size. 256 → 176 is the ratio the .ico was tuned at;
# 192 → 132 and 512 → 352 divide without a remainder.
round_plate() { # size → $work/round-<size>.png
  local size=$1
  local centre="$((size / 2 - 1)).5"
  magick -size "${size}x${size}" xc:none -fill "$plate" \
    -draw "circle $centre,$centre $centre,-0.5" "$work/plate-$size.png"
  magick "$work/plate-$size.png" "$(mark $((size * 176 / 256)))" -gravity center -compose over -composite \
    -strip -define png:exclude-chunks=date,time "$work/round-$size.png"
}

# The square plate: the plate colour edge to edge, no alpha channel at all, the mark at
# 11/16 of the width (the round plate's 176/256, see the header) rounded down to an even
# number so it centres on whole pixels: 352 of 512, 122 of 180. The depth is pinned because
# a Q16 ImageMagick writes 16-bit channels once the alpha is off, which doubles the file
# for nothing a launcher can show.
square_plate() { # size → $work/square-<size>.png
  local size=$1
  magick -size "${size}x${size}" "xc:$plate" "$(mark $((size * 11 / 16 / 2 * 2)))" -gravity center -compose over -composite \
    -alpha off -depth 8 -define png:color-type=2 -strip -define png:exclude-chunks=date,time "$work/square-$size.png"
}

round_plate 256
round_plate 192
round_plate 512
square_plate 512
square_plate 180

# The 256 stays the plain icon at that size and the base of the .ico; the manifest's two
# `any` icons are the same composition at 192 and 512.
cp "$work/round-256.png" public/favicon-256.png
cp "$work/round-192.png" public/icon-192.png
cp "$work/round-512.png" public/icon-512.png

# The maskable icon and the touch icon share the square plate; iOS reads its 180 from
# the `apple-touch-icon` link, Android its 512 from the manifest.
cp "$work/square-512.png" public/icon-512-maskable.png
cp "$work/square-180.png" public/apple-touch-icon.png

for size in 16 32 48; do
  magick "$work/round-256.png" -resize "${size}x${size}" -strip "$work/ico-$size.png"
done
magick "$work/ico-16.png" "$work/ico-32.png" "$work/ico-48.png" public/favicon.ico

printf 'wrote public/favicon.ico (16/32/48), public/favicon-256.png, public/icon-192.png, public/icon-512.png, public/icon-512-maskable.png and public/apple-touch-icon.png from %s\n' "$src"
