#!/usr/bin/env bash
set -euo pipefail

# Regenerate all gitignored icon variants from tracked source masters.
# Requires: ImageMagick 7+ (`magick`), `cargo tauri icon`
#
# Run this when the base icon design changes or after a fresh clone.
# Outputs stay local under icons/ and are copied into platform folders by
# apply-icon-variant.sh.

cd "$(dirname "$0")/.."

FONT="/System/Library/Fonts/Supplemental/Arial Bold.ttf"
SRC=icons/source
WORK=icons/.generated

# Start clean so reruns do not leave stale generated assets behind.
rm -rf "$WORK" icons/dev icons/beta icons/ga
mkdir -p "$WORK" icons/dev icons/beta icons/ga

make_dev_medallion() {
  local input="$1"
  local stroke="$2"
  local output="$3"

  magick "$input" \
    -crop 760x760+130+120 +repage \
    \( -size 760x760 xc:none -fill white -draw "ellipse 388,380 325,352 0,360" \) \
    -alpha Off -compose CopyOpacity -composite \
    \( -size 760x760 xc:none -stroke "$stroke" -strokewidth 16 -fill none -draw "ellipse 388,380 318,345 0,360" \) \
    -compose over -composite \
    "$output"
}

echo "==> Generating blueprint base..."

# Blueprint light: grayscale → blue tint + grid overlay
magick "$SRC/base-1024.png" \
  -grayscale Rec709Luminance \
  -fill '#1565C0' -tint 80 \
  "$WORK/blueprint-tinted-1024.png"

# Grid tile pattern
magick -size 64x64 xc:none \
  -stroke 'rgba(255,255,255,0.40)' -strokewidth 1.4 \
  -draw "line 0,0 63,0" -draw "line 0,0 0,63" \
  "$WORK/grid-tile.png"
magick -size 256x256 xc:none \
  -stroke 'rgba(255,255,255,0.65)' -strokewidth 2.5 \
  -draw "line 0,0 255,0" -draw "line 0,0 0,255" \
  "$WORK/grid-major-tile.png"
magick -size 1024x1024 tile:"$WORK/grid-tile.png" "$WORK/grid-overlay-1024.png"
magick -size 1024x1024 tile:"$WORK/grid-major-tile.png" "$WORK/grid-major-overlay-1024.png"
magick "$WORK/grid-overlay-1024.png" "$WORK/grid-major-overlay-1024.png" -composite \
  "$WORK/grid-overlay-1024.png"

# Desktop-specific blueprint grid: fewer, brighter white lines so the pattern
# still reads after the icon is reduced into the Dock.
magick -size 48x48 xc:none \
  -stroke 'rgba(255,255,255,0.78)' -strokewidth 2.2 \
  -draw "line 0,0 47,0" -draw "line 0,0 0,47" \
  "$WORK/grid-desktop-tile.png"
magick -size 192x192 xc:none \
  -stroke 'rgba(255,255,255,1.0)' -strokewidth 3.8 \
  -draw "line 0,0 191,0" -draw "line 0,0 0,191" \
  "$WORK/grid-desktop-major-tile.png"
magick -size 1024x1024 tile:"$WORK/grid-desktop-tile.png" "$WORK/grid-desktop-overlay-1024.png"
magick -size 1024x1024 tile:"$WORK/grid-desktop-major-tile.png" "$WORK/grid-desktop-major-overlay-1024.png"
magick "$WORK/grid-desktop-overlay-1024.png" "$WORK/grid-desktop-major-overlay-1024.png" -composite \
  "$WORK/grid-desktop-overlay-1024.png"

magick "$WORK/blueprint-tinted-1024.png" "$WORK/grid-overlay-1024.png" -composite \
  "$WORK/blueprint-base-1024.png"

# Clean blueprint background (solid blue + grid, no coin ghost)
magick -size 1024x1024 xc:'#1565C0' "$WORK/grid-overlay-1024.png" -composite \
  "$WORK/blueprint-clean-1024.png"

# Blueprint dark
magick "$SRC/base-1024.png" \
  -grayscale Rec709Luminance \
  -fill '#0D47A1' -tint 80 \
  -modulate 60,100,100 \
  "$WORK/blueprint-dark-base-1024.png"
magick "$WORK/blueprint-dark-base-1024.png" "$WORK/grid-overlay-1024.png" -composite \
  "$WORK/blueprint-dark-1024.png"

# Desktop mask: padded squircle on a transparent canvas so the Dock footprint
# stays controlled without turning the icon into a circle or a raw square.
magick -size 1024x1024 xc:none \
  -fill white \
  -draw "roundrectangle 112,112 911,911 178,178" \
  "$WORK/desktop-mask-1024.png"

echo "==> Generating BETA ribbon..."
magick -size 400x400 xc:none \
  -fill '#4CAF50' \
  -draw "polygon 400,0 400,100 100,400 0,400 0,300 300,0" \
  -fill white -font "$FONT" -pointsize 48 \
  -gravity center -draw "rotate -45 text 0,-10 'BETA'" \
  "$WORK/ribbon-beta.png"

echo "==> Assembling master icons..."

# ── GA: clean original ──
cp "$SRC/base-1024.png" icons/ga/ga-light-1024.png
magick "$SRC/base-1024.png" -modulate 55,90,100 icons/ga/ga-dark-1024.png

# ── Dev: blueprint background + deliberate coin medallion ──
make_dev_medallion "$SRC/base-1024.png" '#F5E56F' "$WORK/dev-medallion-light.png"
magick "$SRC/base-1024.png" -modulate 70,90,100 /tmp/base-dev-dark.png
make_dev_medallion /tmp/base-dev-dark.png '#E6D773' "$WORK/dev-medallion-dark.png"
magick "$WORK/blueprint-base-1024.png" \
  "$WORK/dev-medallion-light.png" \
  -gravity center -composite \
  icons/dev/dev-light-1024.png
magick "$WORK/blueprint-dark-1024.png" \
  "$WORK/dev-medallion-dark.png" \
  -gravity center -composite \
  icons/dev/dev-dark-1024.png

# ── Beta: original icon + BETA ribbon ──
magick "$SRC/base-1024.png" \
  "$WORK/ribbon-beta.png" \
  -gravity NorthEast -geometry +0+0 -composite \
  icons/beta/beta-light-1024.png

magick "$SRC/base-1024.png" -modulate 55,90,100 /tmp/base-dark.png
magick /tmp/base-dark.png \
  "$WORK/ribbon-beta.png" \
  -gravity NorthEast -geometry +0+0 -composite \
  icons/beta/beta-dark-1024.png

echo "==> Generating platform assets..."

declare -A ANDROID_DENSITIES=(
  [mipmap-mdpi]="48:108"
  [mipmap-hdpi]="72:162"
  [mipmap-xhdpi]="96:216"
  [mipmap-xxhdpi]="144:324"
  [mipmap-xxxhdpi]="192:432"
)

for variant in dev beta ga; do
  light="icons/${variant}/${variant}-light-1024.png"
  dark="icons/${variant}/${variant}-dark-1024.png"

  # ── iOS: single 1024x1024 per appearance ──
  ios="icons/${variant}/ios"
  mkdir -p "$ios"
  cp "$light" "${ios}/AppIcon-light-1024.png"
  cp "$dark"  "${ios}/AppIcon-dark-1024.png"

  # ── Android adaptive icons ──
  for density in "${!ANDROID_DENSITIES[@]}"; do
    IFS=: read -r launcher_size fg_size <<< "${ANDROID_DENSITIES[$density]}"
    dir="icons/${variant}/android/${density}"
    mkdir -p "$dir"
    magick "$light" -resize ${launcher_size}x${launcher_size} "${dir}/ic_launcher.png"
    inner=$((fg_size * 66 / 100))
    fg_source="$light"
    if [ "$variant" = "dev" ]; then
      fg_source="$WORK/dev-medallion-light.png"
      inner=$((fg_size * 50 / 100))
    fi
    magick "$fg_source" -resize ${inner}x${inner} \
      -gravity center -background none -extent ${fg_size}x${fg_size} \
      "${dir}/ic_launcher_foreground.png"
    if [ "$variant" = "dev" ]; then
      magick "$WORK/blueprint-clean-1024.png" -resize ${fg_size}x${fg_size} \
        "${dir}/ic_launcher_background.png"
    else
      magick -size ${fg_size}x${fg_size} gradient:'#4DD8E0'-'#E8E060' -rotate 135 \
        "${dir}/ic_launcher_background.png"
    fi
    magick "$fg_source" -resize ${inner}x${inner} -colorspace Gray \
      -gravity center -background none -extent ${fg_size}x${fg_size} \
      "${dir}/ic_launcher_monochrome.png"
  done
  mkdir -p "icons/${variant}/android/mipmap-anydpi-v26"
  cat > "icons/${variant}/android/mipmap-anydpi-v26/ic_launcher.xml" << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@mipmap/ic_launcher_background"/>
  <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
  <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
XMLEOF

  # ── Tauri / Desktop ──
  tdir="icons/${variant}/tauri"
  mkdir -p "$tdir"

  if [ "$variant" = "dev" ]; then
    # Dev: padded squircle carrying the blueprint field and a smaller medallion.
    magick -size 1024x1024 xc:'#14539A' "$WORK/grid-desktop-overlay-1024.png" -composite \
      "$WORK/desktop-mask-1024.png" \
      -alpha Off -compose CopyOpacity -composite \
      -depth 8 \
      /tmp/masked-1024.png
    magick /tmp/masked-1024.png \
      \( "$WORK/dev-medallion-light.png" -resize 580x580 \) \
      -gravity center -composite \
      /tmp/masked-1024.png
  else
    # GA/Beta: keep their current artwork, but give desktop the same padded
    # squircle footprint as dev.
    magick "$light" \
      -resize 800x800 \
      -gravity center -background none -extent 1024x1024 \
      "$WORK/desktop-mask-1024.png" \
      -alpha Off -compose CopyOpacity -composite \
      -depth 8 \
      /tmp/masked-1024.png
  fi

  # Tauri requires 8-bit RGBA PNG (color-type 6)
  magick /tmp/masked-1024.png \
    -resize 512x512 \
    -depth 8 -type TrueColorAlpha -define png:color-type=6 \
    "${tdir}/icon.png"

  # Windows ICO (square is fine, OS applies its own shape)
  magick "$light" -depth 8 \
    \( -clone 0 -resize 16x16 \) \
    \( -clone 0 -resize 32x32 \) \
    \( -clone 0 -resize 48x48 \) \
    \( -clone 0 -resize 256x256 \) \
    -delete 0 "${tdir}/icon.ico"

  # macOS ICNS via Tauri's icon generator. iconutil was rejecting otherwise
  # valid iconsets on this machine, while `cargo tauri icon` produced a valid
  # .icns from the same masked 1024x1024 source.
  TAURI_ICON_DIR=$(mktemp -d)
  cargo tauri icon /tmp/masked-1024.png --output "$TAURI_ICON_DIR" >/dev/null
  cp "$TAURI_ICON_DIR/icon.icns" "${tdir}/icon.icns"
  rm -rf "$TAURI_ICON_DIR"

  # ── Web ──
  wdir="icons/${variant}/web"
  mkdir -p "$wdir"
  magick "$light" \
    \( -clone 0 -resize 16x16 \) \
    \( -clone 0 -resize 32x32 \) \
    -delete 0 "${wdir}/favicon.ico"
  magick "$light" -resize 180x180 "${wdir}/apple-touch-icon.png"
  magick "$light" -resize 192x192 "${wdir}/icon-192.png"
  magick "$light" -resize 512x512 "${wdir}/icon-512.png"
done

echo "==> Done! All icon variants generated locally in icons/ (gitignored)"
