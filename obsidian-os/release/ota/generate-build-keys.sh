#!/bin/bash
# Creates a signing key for everything in a build that needs one beyond the seven main keys.
#
#   generate-build-keys.sh <target-files.zip> [keys-directory]
#
# Two kinds of thing need their own key:
#
#   APEX modules, the parts of the system that Google can update separately: ART, which runs every
#   app, conscrypt, which handles every encrypted connection, the media stack, Wi-Fi and forty odd
#   others. Each is signed twice, once for its contents and once for its container.
#
#   A handful of apps that the build signs with a named key of their own rather than one of the
#   main ones.
#
# Without these, an unsigned build keeps the public test keys that ship in the Android source,
# which everybody in the world has a copy of. Anyone could then replace those parts of the system
# and a phone would accept them as genuine.
#
# Run it again whenever a build gains something new. Existing keys are never touched: replacing one
# would orphan every phone running a release signed with the old one.
set -euo pipefail

TARGET_FILES=${1:?"give the target-files zip from the build"}
DEST=${2:-./obsidian-keys}
DAYS=${DAYS:-10000}
SUBJECT=${SUBJECT:-"/CN=OBSIDIAN/O=OBSIDIAN/C=GB"}

command -v openssl >/dev/null || { echo "openssl is required"; exit 1; }
[ -d "$DEST" ] || { echo "no keys directory at $DEST: run generate-keys.sh first"; exit 1; }
export MSYS_NO_PATHCONV=1

# A certificate and its private key, in the form the Android build tools expect.
make_pair() {
  local name=$1
  [ -f "$DEST/$name.pk8" ] && [ -f "$DEST/$name.x509.pem" ] && return 1
  openssl req -new -x509 -newkey rsa:4096 -nodes -sha256 -days "$DAYS" \
    -keyout "$DEST/$name.tmp.pem" -out "$DEST/$name.x509.pem" -subj "$SUBJECT" 2>/dev/null
  openssl pkcs8 -topk8 -outform DER -nocrypt -in "$DEST/$name.tmp.pem" -out "$DEST/$name.pk8"
  rm -f "$DEST/$name.tmp.pem"
  chmod 600 "$DEST/$name.pk8" 2>/dev/null || true
  return 0
}

echo "Reading the build."
# Everything actually packaged, so keys are not made for test apps that were built but not shipped.
present=$(unzip -Z1 "$TARGET_FILES" '*.apk' 2>/dev/null | sed 's|.*/||' | sort -u)

made=0
kept=0

echo "APEX modules:"
apexes=$(unzip -p "$TARGET_FILES" META/apexkeys.txt 2>/dev/null | sed -n 's/^name="\([^"]*\)".*/\1/p' | sed 's/\.apex$//' | sort -u)
for name in $apexes; do
  new=0
  # The payload key signs the module's contents and is checked by Verified Boot, so it is a bare
  # RSA key rather than a certificate.
  if [ ! -f "$DEST/$name.pem" ]; then
    openssl genrsa -out "$DEST/$name.pem" 4096 2>/dev/null
    chmod 600 "$DEST/$name.pem" 2>/dev/null || true
    new=1
  fi
  make_pair "$name" && new=1
  if [ "$new" = 1 ]; then made=$((made + 1)); printf '  %-44s made\n' "$name"; else kept=$((kept + 1)); fi
done

echo "Apps signed with a key of their own:"
# apkcerts.txt lists every app the build produced, including test ones that are not shipped. Only
# the apps actually packaged matter, and PRESIGNED means an app that must keep the signature it
# came with, such as a prebuilt from another vendor.
while IFS= read -r line; do
  [ -n "$line" ] || continue
  apk=$(printf '%s' "$line" | sed -n 's/^name="\([^"]*\)".*/\1/p')
  cert=$(printf '%s' "$line" | sed -n 's/.*certificate="\([^"]*\)".*/\1/p')
  [ -n "$apk" ] && [ -n "$cert" ] || continue
  [ "$cert" = "PRESIGNED" ] && continue
  printf '%s\n' "$present" | grep -qxF "$apk" || continue
  name=$(printf '%s' "$cert" | sed 's|.*/||; s|\.x509\.pem$||')
  case "$name" in
    releasekey|platform|shared|media|networkstack|sdk_sandbox|bluetooth) continue ;;
  esac
  if make_pair "$name"; then
    made=$((made + 1)); printf '  %-44s made\n' "$name"
  else
    kept=$((kept + 1))
  fi
done <<< "$(unzip -p "$TARGET_FILES" META/apkcerts.txt 2>/dev/null)"

echo
echo "$made new, $kept already had keys, all in $DEST."
[ "$made" -gt 0 ] && echo "Back the directory up again: these matter as much as the release key itself."
exit 0
