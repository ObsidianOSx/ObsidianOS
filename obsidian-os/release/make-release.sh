#!/bin/bash
# Turns a finished Pixel build into a release: the factory zip that both the browser installer and
# the flashing scripts expect, its SHA-256, and the latest-<device>.json the browser installer reads.
#
#   make-release.sh <version>        for example: make-release.sh 2026.09.21-test
#
# The zip holds bootloader-*.img, radio-*.img and image-*.zip in one folder, named the way factory
# images for Pixels are named; fastboot.js finds each part by those names. The firmware versions
# are read from the image's own android-info.txt and checked against the firmware files, because a
# mismatch makes the phone refuse the image halfway through flashing.
set -euo pipefail

VERSION=${1:?"give a version, for example 2026.09.21-test"}
DEVICE=${DEVICE:-shiba}
KEYS=${KEYS:-test}
# The name people know each phone by, used in the release name, the scripts and their messages.
case "$DEVICE" in
  shiba) MODEL="Pixel 8" ;;
  tokay) MODEL="Pixel 9" ;;
  panther) MODEL="Pixel 7" ;;
  oriole) MODEL="Pixel 6" ;;
  frankel) MODEL="Pixel 10" ;;
  *) echo "no model name known for $DEVICE: add it here first"; exit 1 ;;
esac
OUT=${OUT:-$HOME/os/out/target/product/$DEVICE}
DEST=${DEST:-$HOME/releases}
HERE=$(cd "$(dirname "$0")" && pwd)
NAME="obsidian-$DEVICE-$VERSION"
IMG="$OUT/$DEVICE-img.zip"

for f in "$IMG" "$OUT/bootloader.img" "$OUT/radio.img"; do
  [ -f "$f" ] || { echo "missing $f"; exit 1; }
done

# The image stamps the version it was built with. If the two disagree, a phone would report one
# version while the download that produced it claims another, which makes updates impossible to
# reason about, so stop rather than publish the mismatch.
BAKED=""
for p in "$OUT/product/etc/build.prop" "$OUT/product/build.prop" "$OUT/system/build.prop"; do
  [ -f "$p" ] && BAKED=$(sed -n 's/^ro\.obsidian\.version=//p' "$p" | head -1) && [ -n "$BAKED" ] && break
done
if [ -z "$BAKED" ]; then
  echo "note: this image predates version stamping, so nothing inside it names the release"
elif [ "$BAKED" != "$VERSION" ]; then
  echo "the image says it is $BAKED but you asked to release it as $VERSION"
  echo "build with OBSIDIAN_VERSION=$VERSION, or release it under the name it was built with"
  exit 1
fi

INFO=$(unzip -p "$IMG" android-info.txt)
BL=$(printf '%s\n' "$INFO" | sed -n 's/^require version-bootloader=//p')
RADIO=$(printf '%s\n' "$INFO" | sed -n 's/^require version-baseband=//p')
[ -n "$BL" ] && [ -n "$RADIO" ] || { echo "android-info.txt names no firmware versions"; exit 1; }
grep -aqF "$BL" "$OUT/bootloader.img" || { echo "bootloader.img is not version $BL"; exit 1; }
grep -aqF "$RADIO" "$OUT/radio.img" || { echo "radio.img is not version $RADIO"; exit 1; }

BL_FILE="bootloader-$DEVICE-$BL.img"
RADIO_FILE="radio-$DEVICE-$RADIO.img"
IMAGE_FILE="image-$DEVICE-$VERSION.zip"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/$NAME"
cp "$OUT/bootloader.img" "$work/$NAME/$BL_FILE"
cp "$OUT/radio.img" "$work/$NAME/$RADIO_FILE"
cp "$IMG" "$work/$NAME/$IMAGE_FILE"
fill() {
  sed -e "s|@BOOTLOADER@|$BL_FILE|g" -e "s|@RADIO@|$RADIO_FILE|g" \
      -e "s|@IMAGE@|$IMAGE_FILE|g" -e "s|@VERSION@|$VERSION|g" \
      -e "s|@DEVICE@|$DEVICE|g" -e "s|@MODEL@|$MODEL|g" "$1"
}
fill "$HERE/flash-all.sh" > "$work/$NAME/flash-all.sh"
fill "$HERE/flash-all.bat" > "$work/$NAME/flash-all.bat"
fill "$HERE/README.txt" > "$work/$NAME/README.txt"
chmod 755 "$work/$NAME/flash-all.sh"

mkdir -p "$DEST"
rm -f "$DEST/$NAME.zip"
# The image zip is already compressed, so it is stored as it is rather than squeezed a second time.
(cd "$work" && zip -q -r -n .zip "$DEST/$NAME.zip" "$NAME")

SIZE=$(stat -c %s "$DEST/$NAME.zip")
SHA=$(sha256sum "$DEST/$NAME.zip" | cut -d' ' -f1)
(cd "$DEST" && printf '%s  %s\n' "$SHA" "$NAME.zip" > "$NAME.zip.sha256")
# One index per phone: the browser installer asks the phone what it is, then reads latest-<device>.json.
cat > "$DEST/latest-$DEVICE.json" <<JSON
{
  "name": "OBSIDIAN $VERSION for the $MODEL",
  "version": "$VERSION",
  "device": "$DEVICE",
  "model": "$MODEL",
  "keys": "$KEYS",
  "file": "releases/$NAME.zip",
  "size": $SIZE,
  "sha256": "$SHA"
}
JSON

echo "$DEST/$NAME.zip"
echo "  $SIZE bytes, sha256 $SHA"
# GitHub refuses release files of 2 GiB or more.
[ "$SIZE" -lt 2147483648 ] || echo "  WARNING: too large to attach to a GitHub release"
