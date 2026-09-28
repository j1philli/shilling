#!/usr/bin/env bash
set -euo pipefail

# Regenerate every committed platform icon from icons/source/base-1024.png.
# Requires: ImageMagick 7+ (`magick`), `cargo tauri icon`, python3
#
# Run this only when the source artwork changes, then commit the results.
# Builds never run it; they pick a variant (dev/beta/ga) from the committed
# outputs instead. Store betas (TestFlight, Play testing tracks) promote the same
# binary to production, so iOS and Android have no beta icon:
#   iOS      app/ios-app/src/Assets.xcassets/AppIcon{,-Dev}.appiconset
#            (selected per Xcode configuration via ASSETCATALOG_COMPILER_APPICON_NAME)
#   Android  app/android-app/res/mipmap-*            (GA; release builds)
#            app/android-app/src/debug/res/mipmap-*  (dev; debug builds)
#   Desktop  src-tauri/icons/{ga,beta,dev}/          (selected via tauri.<variant>.conf.json)
#   Web      app/web-app/icons/{ga,beta,dev}/        (selected by build-web.sh)
#   In-app   app/shared-ui/composeResources/drawable/app_logo.png (GA)

cd "$(dirname "$0")/.."

FONT="${ICON_FONT:-/System/Library/Fonts/Supplemental/Arial Bold.ttf}"
if [ ! -f "$FONT" ]; then
  echo "ERROR: font not found: $FONT (set ICON_FONT to a bold TTF)" >&2
  exit 1
fi

SRC=icons/source
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

IOS_ASSETS=app/ios-app/src/Assets.xcassets
ANDROID_RES=app/android-app/res
ANDROID_DEBUG_RES=app/android-app/src/debug/res
TAURI_ICONS=src-tauri/icons
WEB_ICONS=app/web-app/icons
APP_LOGO=app/shared-ui/composeResources/drawable/app_logo.png

# Omit PNG timestamp chunks so regenerating unchanged artwork is a no-op in git.
magick() {
  command magick -define png:exclude-chunks=date,time "$@"
}

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
cp "$SRC/base-1024.png" "$WORK/ga-light-1024.png"
magick "$SRC/base-1024.png" -modulate 55,90,100 "$WORK/ga-dark-1024.png"

# ── Dev: blueprint background + deliberate coin medallion ──
make_dev_medallion "$SRC/base-1024.png" '#F5E56F' "$WORK/dev-medallion-light.png"
magick "$SRC/base-1024.png" -modulate 70,90,100 "$WORK/base-dev-dark.png"
make_dev_medallion "$WORK/base-dev-dark.png" '#E6D773' "$WORK/dev-medallion-dark.png"
magick "$WORK/blueprint-base-1024.png" \
  "$WORK/dev-medallion-light.png" \
  -gravity center -composite \
  "$WORK/dev-light-1024.png"
magick "$WORK/blueprint-dark-1024.png" \
  "$WORK/dev-medallion-dark.png" \
  -gravity center -composite \
  "$WORK/dev-dark-1024.png"

# ── Beta: original icon + BETA ribbon ──
magick "$SRC/base-1024.png" \
  "$WORK/ribbon-beta.png" \
  -gravity NorthEast -geometry +0+0 -composite \
  "$WORK/beta-light-1024.png"
magick "$WORK/ga-dark-1024.png" \
  "$WORK/ribbon-beta.png" \
  -gravity NorthEast -geometry +0+0 -composite \
  "$WORK/beta-dark-1024.png"

echo "==> Writing platform assets..."

write_ios_iconset() {
  local iconset="$1"
  local light="$2"
  local dark="$3"

  rm -rf "$iconset"
  mkdir -p "$iconset"
  cp "$light" "$iconset/AppIcon.png"
  cp "$dark"  "$iconset/AppIcon-dark.png"
  cat > "$iconset/Contents.json" << 'EOF'
{
  "images" : [
    {
      "filename" : "AppIcon.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    },
    {
      "appearances" : [
        {
          "appearance" : "luminosity",
          "value" : "dark"
        }
      ],
      "filename" : "AppIcon-dark.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
EOF
}

for variant in ga beta dev; do
  light="$WORK/${variant}-light-1024.png"
  dark="$WORK/${variant}-dark-1024.png"

  # ── iOS: single 1024x1024 per appearance (no beta, see header) ──
  case "$variant" in
    ga)  write_ios_iconset "$IOS_ASSETS/AppIcon.appiconset" "$light" "$dark" ;;
    dev) write_ios_iconset "$IOS_ASSETS/AppIcon-Dev.appiconset" "$light" "$dark" ;;
  esac
  # ── Tauri / Desktop ──
  tdir="$TAURI_ICONS/$variant"
  rm -rf "$tdir"
  mkdir -p "$tdir"
  masked="$WORK/${variant}-desktop-1024.png"

  if [ "$variant" = "dev" ]; then
    # Dev: padded squircle carrying the blueprint field and a smaller medallion.
    magick -size 1024x1024 xc:'#14539A' "$WORK/grid-desktop-overlay-1024.png" -composite \
      "$WORK/desktop-mask-1024.png" \
      -alpha Off -compose CopyOpacity -composite \
      -depth 8 \
      "$masked"
    magick "$masked" \
      \( "$WORK/dev-medallion-light.png" -resize 580x580 \) \
      -gravity center -composite \
      "$masked"
  else
    # GA/Beta: keep their artwork, but give desktop the same padded squircle
    # footprint as dev.
    magick "$light" \
      -resize 800x800 \
      -gravity center -background none -extent 1024x1024 \
      "$WORK/desktop-mask-1024.png" \
      -alpha Off -compose CopyOpacity -composite \
      -depth 8 \
      "$masked"
  fi

  # Tauri requires 8-bit RGBA PNG (color-type 6)
  magick "$masked" \
    -resize 512x512 \
    -depth 8 -type TrueColorAlpha -define png:color-type=6 \
    "$tdir/icon.png"

  # Windows ICO (square is fine, OS applies its own shape)
  magick "$light" -depth 8 \
    \( -clone 0 -resize 16x16 \) \
    \( -clone 0 -resize 32x32 \) \
    \( -clone 0 -resize 48x48 \) \
    \( -clone 0 -resize 256x256 \) \
    -delete 0 "$tdir/icon.ico"

  # macOS ICNS via Tauri's icon generator. iconutil was rejecting otherwise
  # valid iconsets on this machine, while `cargo tauri icon` produced a valid
  # .icns from the same masked 1024x1024 source.
  cargo tauri icon "$masked" --output "$WORK/tauri-$variant" >/dev/null 2>&1
  # It writes chunks in hash-map order; sort them so reruns are byte-identical.
  python3 - "$WORK/tauri-$variant/icon.icns" "$tdir/icon.icns" << 'PYEOF'
import struct, sys
data = open(sys.argv[1], "rb").read()
chunks, i = [], 8
while i < len(data):
    size = struct.unpack(">I", data[i + 4:i + 8])[0]
    chunks.append(data[i:i + size])
    i += size
body = b"".join(sorted(chunks, key=lambda c: c[:4]))
open(sys.argv[2], "wb").write(b"icns" + struct.pack(">I", 8 + len(body)) + body)
PYEOF

  # ── Web ──
  wdir="$WEB_ICONS/$variant"
  rm -rf "$wdir"
  mkdir -p "$wdir"
  magick "$light" \
    \( -clone 0 -resize 16x16 \) \
    \( -clone 0 -resize 32x32 \) \
    -delete 0 "$wdir/favicon.ico"
  magick "$light" -resize 180x180 "$wdir/apple-touch-icon.png"
done

# ── Android adaptive icons ──
# GA goes in the main res dir; dev goes in the debug source set, which Android
# merges over main for debug builds only.
write_android_icons() {
  local variant="$1"
  local res="$2"
  local light="$WORK/${variant}-light-1024.png"
  local fg_source="$light"
  local fg_percent=66
  if [ "$variant" = "dev" ]; then
    fg_source="$WORK/dev-medallion-light.png"
    fg_percent=50
  fi

  for spec in mipmap-mdpi:48:108 mipmap-hdpi:72:162 mipmap-xhdpi:96:216 mipmap-xxhdpi:144:324 mipmap-xxxhdpi:192:432; do
    IFS=: read -r density launcher_size fg_size <<< "$spec"
    local dir="$res/$density"
    local inner=$((fg_size * fg_percent / 100))
    mkdir -p "$dir"
    magick "$light" -resize ${launcher_size}x${launcher_size} "$dir/ic_launcher.png"
    magick "$fg_source" -resize ${inner}x${inner} \
      -gravity center -background none -extent ${fg_size}x${fg_size} \
      "$dir/ic_launcher_foreground.png"
    if [ "$variant" = "dev" ]; then
      magick "$WORK/blueprint-clean-1024.png" -resize ${fg_size}x${fg_size} \
        "$dir/ic_launcher_background.png"
    else
      magick -size ${fg_size}x${fg_size} gradient:'#4DD8E0'-'#E8E060' -rotate 135 \
        "$dir/ic_launcher_background.png"
    fi
    magick "$fg_source" -resize ${inner}x${inner} -colorspace Gray \
      -gravity center -background none -extent ${fg_size}x${fg_size} \
      "$dir/ic_launcher_monochrome.png"
  done
  mkdir -p "$res/mipmap-anydpi-v26"
  cat > "$res/mipmap-anydpi-v26/ic_launcher.xml" << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@mipmap/ic_launcher_background"/>
  <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
  <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
XMLEOF
}

write_android_icons ga "$ANDROID_RES"
write_android_icons dev "$ANDROID_DEBUG_RES"

# ── In-app logo (onboarding) ──
mkdir -p "$(dirname "$APP_LOGO")"
magick "$WORK/ga-light-1024.png" -resize 512x512 "$APP_LOGO"

echo "==> Done. Review and commit the regenerated assets."
