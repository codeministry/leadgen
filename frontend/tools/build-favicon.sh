#!/usr/bin/env bash
# Renders the favicon set from the mark.
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
# Needs `rsvg-convert` (librsvg) and ImageMagick 7 (`magick`). Run from `frontend/`.
# Output is deterministic: no timestamps are written, so running it twice leaves
# `git diff` empty (ISC-239).

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

# The plate is a circle, so the mark has to fit the inscribed square rather than the
# full 256; 176 of 256 leaves the ring's open end clear of the curve.
rsvg-convert --width 176 --height 176 "$work/mark-coloured.svg" > "$work/mark-176.png"

magick -size 256x256 xc:none -fill "$plate" \
  -draw 'circle 127.5,127.5 127.5,-0.5' "$work/plate.png"
magick "$work/plate.png" "$work/mark-176.png" -gravity center -compose over -composite \
  -strip -define png:exclude-chunks=date,time "$work/icon-256.png"

# The touch icon keeps the same circular plate as the .ico rather than shipping square.
# iOS masks a home-screen icon itself, so on a phone the corners outside the circle are
# cut twice over; everywhere the file is used as a plain 256px icon it matches the tab.
cp "$work/icon-256.png" public/favicon-256.png

for size in 16 32 48; do
  magick "$work/icon-256.png" -resize "${size}x${size}" -strip "$work/ico-$size.png"
done
magick "$work/ico-16.png" "$work/ico-32.png" "$work/ico-48.png" public/favicon.ico

printf 'wrote public/favicon.ico (16/32/48) and public/favicon-256.png from %s\n' "$src"
