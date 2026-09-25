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
#
# Scratch space goes next to the output rather than in /tmp. The tools unpack the entire build to
# work on it, which is several times its own size, and /tmp is a small memory backed disk on some
# machines: under WSL it is under 4 GB, and the run dies partway through with nothing but
# "No space left on device" to explain itself.
mkdir -p "$OUT"
WORK=$(mktemp -d "$(cd "$OUT" && pwd)/signing-scratch.XXXXXX")
export TMPDIR="$WORK"
trap 'rm -rf "$WORK"' EXIT
available=$(df -Pk "$WORK" | awk 'NR==2 {print int($4 / 1048576)}')
[ "$available" -ge 40 ] || echo "      warning: only ${available} GB free here, and signing needs about 40"
echo "[1/7] unpacking the signing tools"
unzip -q -o "$OTATOOLS" -d "$WORK/otatools"
export PATH="$WORK/otatools/bin:$PATH"
# The tools in bin/ are self contained executables carrying their own Python. Running the .py files
# in releasetools/ directly fails on an import, because nothing sets up the module path they expect.
TOOLS="$WORK/otatools/bin"
chmod +x "$TOOLS"/* 2>/dev/null || true
[ -x "$TOOLS/sign_target_files_apks" ] || { echo "this otatools has no bin/sign_target_files_apks"; exit 1; }

SIGNED="$OUT/$DEVICE-$VERSION-signed-target-files.zip"

# Verified Boot signs the top level vbmeta, and on these phones some partitions are chained to
# their own keys. Which ones exist differs by phone, so read it from the build rather than guessing:
# passing a flag for a partition the build does not have makes signing fail.
echo "[2/7] working out which partitions this phone signs separately"
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

# Each APEX module carries its own signature, and an unsigned build still has the public test keys
# from the Android source in it. Those have to be replaced too, or those parts of the system could
# be swapped by anyone, since everybody has the same test keys.
echo "[3/7] pointing every APEX module at our own keys"
APEX_ARGS=()
missing=""
while read -r apex; do
  [ -n "$apex" ] || continue
  base=${apex%.apex}
  if [ -f "$KEYS/$base.pem" ] && [ -f "$KEYS/$base.pk8" ] && [ -f "$KEYS/$base.x509.pem" ]; then
    APEX_ARGS+=(--extra_apex_payload_key "$apex=$KEYS/$base.pem" --extra_apks "$apex=$KEYS/$base")
  else
    missing="$missing $base"
  fi
done <<< "$(unzip -p "$TARGET_FILES" META/apexkeys.txt | sed -n 's/^name="\([^"]*\)".*/\1/p' | sort -u)"
if [ -n "$missing" ]; then
  echo "      no keys for:$missing"
  echo "      run generate-build-keys.sh against this build first, or those modules would keep the"
  echo "      public test keys that ship in the Android source."
  exit 1
fi
echo "      ${#APEX_ARGS[@]} arguments for $(( ${#APEX_ARGS[@]} / 4 )) modules"

# Some modules carry apps inside them, and those are signed separately again. The signing tool has
# no way to guess a key for them and stops on the first one it meets, so find them by looking in
# the modules rather than keeping a hand written list that would rot with every Android release.
echo "      looking for apps packaged inside those modules"
# One pass over the archive rather than one per module: pulling 46 modules out of a 3.8 GB zip
# separately means seeking through the whole thing 46 times, which takes minutes instead of seconds.
unzip -q -o "$TARGET_FILES" '*/apex/*.apex' -d "$WORK/apexes" 2>/dev/null || true
extracted=$(find "$WORK/apexes" -name '*.apex' 2>/dev/null | wc -l)
echo "      unpacked $extracted modules to look inside"
# The apps are not zip entries: a module holds a filesystem image, apex_payload.img, and the apps
# live inside that. deapexer reads it. Every step tolerates failure, because a module with no apps
# in it would otherwise end the whole run under set -e without saying why.
# deapexer needs to be told where its own helpers are, or it refuses with a message about
# ANDROID_HOST_OUT and lists nothing, which looks exactly like a module with no apps in it.
NESTED=$(
  find "$WORK/apexes" -type f -name \*.apex 2>/dev/null | while read -r apex; do
    "$TOOLS/deapexer" --debugfs_path "$TOOLS/debugfs_static" --fsckerofs_path "$TOOLS/fsck.erofs" \
      list "$apex" 2>/dev/null || true
  done | grep -i '\.apk$' | sed 's|.*/||' | sort -u
) || true
rm -rf "$WORK/apexes"
count=0
for apk in $NESTED; do
  APEX_ARGS+=(--extra_apks "$apk=$KEYS/releasekey")
  count=$((count + 1))
done
echo "      $count app$([ "$count" = 1 ] || echo s) inside modules, signed with the release key"

echo "[4/7] signing the image, which replaces every test key in it"
"$TOOLS/sign_target_files_apks" \
  --default_key_mappings "$KEYS" \
  "${AVB_ARGS[@]}" \
  "${APEX_ARGS[@]}" \
  "$TARGET_FILES" "$SIGNED"

echo "[5/7] publishing the Verified Boot fingerprint"
"$TOOLS/avbtool" extract_public_key --key "$KEYS/avb.pem" --output "$OUT/avb_pkmd.bin"
echo "      avb_pkmd.bin sha256 $(sha256sum "$OUT/avb_pkmd.bin" | cut -d' ' -f1)"

echo "[6/7] building the flashable image, for a phone that does not run OBSIDIAN yet"
"$TOOLS/img_from_target_files" "$SIGNED" "$OUT/$DEVICE-img.zip"

echo "[7/7] building the update packages"
FULL="$OUT/obsidian-ota-$DEVICE-$VERSION-full.zip"
"$TOOLS/ota_from_target_files" \
  --package_key "$KEYS/releasekey" \
  "$SIGNED" "$FULL"
echo "      full: $(stat -c %s "$FULL") bytes"

if [ -n "$PREVIOUS" ]; then
  [ -f "$PREVIOUS" ] || { echo "PREVIOUS is set but $PREVIOUS does not exist"; exit 1; }
  # The version this update starts from, taken from the package itself so the name cannot drift.
  FROM=$(unzip -p "$PREVIOUS" META/misc_info.txt 2>/dev/null | sed -n 's/^build_version=//p')
  FROM=${FROM:-previous}
  INC="$OUT/obsidian-ota-$DEVICE-$VERSION-from-$FROM.zip"
  "$TOOLS/ota_from_target_files" \
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
