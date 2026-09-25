#!/bin/bash
# Signs a build with the OBSIDIAN release keys and produces everything a phone can be given:
# a flashable image for a first install, and update packages for phones already running OBSIDIAN.
#
#   sign-release.sh <device> <version> <unsigned-target-files.zip>
#
# Run this where the keys are, which is never the build server. It needs otatools.zip from the same
# build, because the signing tools have to match the tree that produced the image.
#
# Environment:
#   KEYS       directory from generate-keys.sh            (default ./obsidian-keys)
#   OTATOOLS   otatools.zip from the build                (default ./otatools.zip)
#   PREVIOUS   signed target-files of the release people are updating FROM, to build a small
#              incremental update as well as the full one. Optional, but without it every phone
#              downloads the whole operating system again.
#   OUT        where to write the results                 (default ./signed/<device>-<version>)
#
# What comes out:
#   <device>-<version>-signed-target-files.zip   keep this: next release's incremental needs it
#   <device>-img.zip                             the flashable image, for make-release.sh
#   obsidian-ota-<device>-<version>-full.zip     update for a phone on any older release
#   obsidian-ota-<device>-<version>-from-<v>.zip update for a phone on exactly that release, small
#   avb_pkmd.bin                                 the public Verified Boot key, safe to publish
set -euo pipefail

DEVICE=${1:?"give the device codename, for example shiba"}
VERSION=${2:?"give the version, for example 2026.10.01"}
TARGET_FILES=${3:?"give the unsigned target-files zip from the build"}

KEYS=${KEYS:-./obsidian-keys}
OTATOOLS=${OTATOOLS:-./otatools.zip}
OUT=${OUT:-./signed/$DEVICE-$VERSION}
PREVIOUS=${PREVIOUS:-}

for f in "$TARGET_FILES" "$OTATOOLS"; do
  [ -f "$f" ] || { echo "missing $f"; exit 1; }
done
[ -d "$KEYS" ] || { echo "no keys directory at $KEYS: run generate-keys.sh first"; exit 1; }
for k in releasekey platform shared media networkstack sdk_sandbox bluetooth; do
  [ -f "$KEYS/$k.pk8" ] && [ -f "$KEYS/$k.x509.pem" ] || { echo "$KEYS is missing $k"; exit 1; }
done
[ -f "$KEYS/avb.pem" ] || { echo "$KEYS is missing avb.pem"; exit 1; }

# The signing tools have to be the ones from the build, so unpack them beside the output rather
# than trusting whatever happens to be installed.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
echo "[1/6] unpacking the signing tools"
unzip -q -o "$OTATOOLS" -d "$WORK/otatools"
export PATH="$WORK/otatools/bin:$PATH"
RELEASETOOLS="$WORK/otatools/releasetools"
[ -x "$RELEASETOOLS/sign_target_files_apks.py" ] || chmod +x "$RELEASETOOLS"/*.py 2>/dev/null || true

mkdir -p "$OUT"
SIGNED="$OUT/$DEVICE-$VERSION-signed-target-files.zip"

# Verified Boot signs the top level vbmeta, and on these phones some partitions are chained to
# their own keys. Which ones exist differs by phone, so read it from the build rather than guessing:
# passing a flag for a partition the build does not have makes signing fail.
echo "[2/6] working out which partitions this phone signs separately"
MISC=$(unzip -p "$TARGET_FILES" META/misc_info.txt)
AVB_ARGS=(--avb_vbmeta_key "$KEYS/avb.pem" --avb_vbmeta_algorithm SHA256_RSA4096)
CHAINED=""
for part in system system_ext product vendor vendor_kernel_boot boot init_boot recovery; do
  if printf '%s\n' "$MISC" | grep -q "^avb_${part}_key_path="; then
    AVB_ARGS+=("--avb_${part}_key" "$KEYS/avb.pem" "--avb_${part}_algorithm" "SHA256_RSA4096")
    CHAINED="$CHAINED $part"
  fi
done
echo "      vbmeta${CHAINED:+, plus its own key for:$CHAINED}"

echo "[3/6] signing the image, which replaces every test key in it"
"$RELEASETOOLS/sign_target_files_apks.py" \
  --default_key_mappings "$KEYS" \
  "${AVB_ARGS[@]}" \
  "$TARGET_FILES" "$SIGNED"

echo "[4/6] publishing the Verified Boot fingerprint"
avbtool extract_public_key --key "$KEYS/avb.pem" --output "$OUT/avb_pkmd.bin"
echo "      avb_pkmd.bin sha256 $(sha256sum "$OUT/avb_pkmd.bin" | cut -d' ' -f1)"

echo "[5/6] building the flashable image, for a phone that does not run OBSIDIAN yet"
"$RELEASETOOLS/img_from_target_files.py" "$SIGNED" "$OUT/$DEVICE-img.zip"

echo "[6/6] building the update packages"
FULL="$OUT/obsidian-ota-$DEVICE-$VERSION-full.zip"
"$RELEASETOOLS/ota_from_target_files.py" \
  --package_key "$KEYS/releasekey" \
  "$SIGNED" "$FULL"
echo "      full: $(stat -c %s "$FULL") bytes"

if [ -n "$PREVIOUS" ]; then
  [ -f "$PREVIOUS" ] || { echo "PREVIOUS is set but $PREVIOUS does not exist"; exit 1; }
  # The version this update starts from, taken from the package itself so the name cannot drift.
  FROM=$(unzip -p "$PREVIOUS" META/misc_info.txt 2>/dev/null | sed -n 's/^build_version=//p')
  FROM=${FROM:-previous}
  INC="$OUT/obsidian-ota-$DEVICE-$VERSION-from-$FROM.zip"
  "$RELEASETOOLS/ota_from_target_files.py" \
    --package_key "$KEYS/releasekey" \
    --incremental_from "$PREVIOUS" "$SIGNED" "$INC"
  echo "      incremental from $FROM: $(stat -c %s "$INC") bytes"
else
  echo "      no PREVIOUS given, so no incremental update. Phones will download the whole system."
fi

echo
echo "Done. In $OUT:"
ls -l "$OUT" | awk 'NR>1 {printf "  %12s  %s\n", $5, $9}'
echo
echo "Keep the signed target-files. The next release needs it to build a small update, and without"
echo "it every phone downloads the entire operating system again."
