#!/usr/bin/env bash
# Renders the favicon set from the original mark.
#
# The source is `public/logo.png`, the untracked original artwork — not the
# `<lg-brand-mark>` geometry. The two are the same mark, but the bitmap has the
# proportions the logo was drawn with, and that is what the tab icon shows.
#
# What the script adds is the second colour: the funnel is repainted in the dark
# theme's petrol and the spout in ochre, so the icon carries the same two-colour
# split as the header wordmark. The spout is addressed by its pixel box in
# `logo.png`, so this script is the only thing that knows where it sits —
# regenerate the icons here, never hand-edit them.
#
# Needs ImageMagick 7 (`magick`). Run from the `frontend/` directory.

set -euo pipefail

src=public/logo.png
petrol='#33E3DA'   # lg-dark --color-primary, the logo's own cyan
ochre='#E3A62B'    # lg-dark --color-accent
plate='#0F1618'    # lg-dark --color-base-200

# The spout's box inside logo.png, measured once: the ink collapses to a
# constant x 130..227 below y 537 and runs to the bottom edge of the canvas.
spout_box=104x52+126+535

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The artwork is opaque on white, so the background is keyed out rather than
# masked; the fuzz covers the anti-aliased edge.
magick "$src" -fuzz 14% -transparent white "$work/mark.png"
magick "$work/mark.png" -alpha extract "$work/alpha.png"

# Repaint by re-using the alpha channel: a `-colorize` tint would carry the
# original cyan through and land somewhere between the two colours.
for layer in petrol ochre; do
  case $layer in
    petrol) colour=$petrol ;;
    ochre) colour=$ochre ;;
  esac
  magick -size "$(magick "$src" -format '%wx%h' info:)" "xc:$colour" \
    "$work/alpha.png" -alpha off -compose CopyOpacity -composite "$work/$layer.png"
done

magick "$work/ochre.png" -crop "$spout_box" +repage "$work/spout.png"
magick "$work/petrol.png" "$work/spout.png" -geometry "+${spout_box#*+}" \
  -compose over -composite "$work/two-tone.png"

# The mark is off-centre by design (funnel low left, noise upper right), so it is
# trimmed and re-centred rather than padded.
magick -size 256x256 xc:none -fill "$plate" \
  -draw 'roundrectangle 0,0 255,255 56,56' "$work/plate.png"
magick "$work/two-tone.png" -trim +repage -resize 220x220 \
  -background none -gravity center -extent 256x256 "$work/fitted.png"
magick "$work/plate.png" "$work/fitted.png" -compose over -composite "$work/icon-256.png"

# The touch icon keeps the same rounded plate as the .ico rather than shipping
# square. iOS masks a home-screen icon itself, so on a phone the radius is
# applied twice; everywhere the file is used as a plain 256px icon it matches.
cp "$work/icon-256.png" public/favicon-256.png

for size in 16 32 48; do
  magick "$work/icon-256.png" -resize "${size}x${size}" "$work/ico-$size.png"
done
magick "$work/ico-16.png" "$work/ico-32.png" "$work/ico-48.png" public/favicon.ico

# The header's mark is the same source but not the same treatment: it sits on
# base-100 in both themes and keeps the logo's own cyan, so it is only trimmed
# and scaled. 128px tall is four times the 26px the header shows.
magick "$work/mark.png" -trim +repage -resize x128 -strip public/logo-mark.png

printf 'wrote public/favicon.ico (16/32/48), public/favicon-256.png and public/logo-mark.png\n'
