#!/bin/bash
# Publishes signed update packages, so phones already running OBSIDIAN can update themselves.
#
#   publish-ota.sh <device> <version> <signed-output-directory>
#
# Uploads the update packages and writes updates/<device>.json, which is the only thing a phone
# reads to decide whether there is anything new. The file is written last and in one move, so a
# phone checking midway through never sees a version whose package is not there yet.
#
# Environment:
#   SITE       user@host of the web server            (required)
#   SSH_KEY    key for it                             (optional)
#   WEBROOT    where the site lives on that host      (default /var/www/obsidian)
#   MODEL      the phone's everyday name              (worked out from the codename if not given)
set -euo pipefail

DEVICE=${1:?"give the device codename"}
VERSION=${2:?"give the version"}
DIR=${3:?"give the directory sign-release.sh wrote"}
SITE=${SITE:?"set SITE to user@host of the web server"}
WEBROOT=${WEBROOT:-/var/www/obsidian}
SSH_OPTS=(-o ConnectTimeout=25 -o ServerAliveInterval=30)
[ -n "${SSH_KEY:-}" ] && SSH_OPTS+=(-i "$SSH_KEY")

case "$DEVICE" in
  shiba) MODEL=${MODEL:-"Pixel 8"} ;;
  tokay) MODEL=${MODEL:-"Pixel 9"} ;;
  panther) MODEL=${MODEL:-"Pixel 7"} ;;
  oriole) MODEL=${MODEL:-"Pixel 6"} ;;
  frankel) MODEL=${MODEL:-"Pixel 10"} ;;
  *) MODEL=${MODEL:?"unknown codename $DEVICE: set MODEL yourself"} ;;
esac

FULL=$(ls "$DIR"/obsidian-ota-"$DEVICE"-"$VERSION"-full.zip 2>/dev/null || true)
[ -f "$FULL" ] || { echo "no full update package in $DIR"; exit 1; }

# Send a file up under a temporary name, check it arrived byte for byte, then move it into place.
send() {
  local path=$1 name
  name=$(basename "$path")
  local want
  want=$(sha256sum "$path" | cut -d' ' -f1)
  scp "${SSH_OPTS[@]}" -q "$path" "$SITE:$WEBROOT/updates/$name.part"
  local got
  got=$(ssh "${SSH_OPTS[@]}" "$SITE" "sha256sum $WEBROOT/updates/$name.part | cut -d' ' -f1")
  [ "$want" = "$got" ] || { echo "      $name does not match after upload"; exit 1; }
  ssh "${SSH_OPTS[@]}" "$SITE" "mv $WEBROOT/updates/$name.part $WEBROOT/updates/$name && sudo chmod 644 $WEBROOT/updates/$name"
  printf '      %-52s %12s bytes\n' "$name" "$(stat -c %s "$path")"
  echo "$want"
}

ssh "${SSH_OPTS[@]}" "$SITE" "sudo mkdir -p $WEBROOT/updates && sudo chown \$(whoami) $WEBROOT/updates"

echo "[1/3] uploading the full update"
FULL_SHA=$(send "$FULL" | tail -1)
FULL_SIZE=$(stat -c %s "$FULL")

echo "[2/3] uploading any incremental updates"
entries=()
for inc in "$DIR"/obsidian-ota-"$DEVICE"-"$VERSION"-from-*.zip; do
  [ -f "$inc" ] || continue
  from=$(basename "$inc" .zip); from=${from##*-from-}
  sha=$(send "$inc" | tail -1)
  entries+=("    \"$from\": {\"file\": \"updates/$(basename "$inc")\", \"size\": $(stat -c %s "$inc"), \"sha256\": \"$sha\"}")
done
if [ ${#entries[@]} -eq 0 ]; then
  INCREMENTALS="{}"
  echo "      none: every phone will download the full update"
else
  # Join the entries with commas and newlines, which is the only fiddly part of writing JSON by hand.
  INCREMENTALS=$(printf '{\n%s\n  }' "$(IFS=$'\n'; printf '%s' "${entries[*]}" | sed '$!s/$/,/')")
fi

echo "[3/3] writing updates/$DEVICE.json"
TMP=$(mktemp)
cat > "$TMP" <<JSON
{
  "device": "$DEVICE",
  "model": "$MODEL",
  "version": "$VERSION",
  "published": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "full": {
    "file": "updates/$(basename "$FULL")",
    "size": $FULL_SIZE,
    "sha256": "$FULL_SHA"
  },
  "incremental": $INCREMENTALS
}
JSON
# Check it parses before it goes anywhere: a phone that reads broken metadata cannot update at all.
# Whichever of these the machine has will do; the file is small.
if command -v python3 >/dev/null; then
  python3 -m json.tool "$TMP" >/dev/null || { echo "      the metadata came out malformed, not publishing"; exit 1; }
elif command -v node >/dev/null; then
  node -e 'JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))' "$TMP" || { echo "      the metadata came out malformed, not publishing"; exit 1; }
else
  echo "      warning: no python3 or node here, so the metadata was not checked before publishing"
fi
# Sign the metadata with the same key that signs the operating system. Phones check this before
# they believe a word of it, so a web server that has been taken over cannot invent a version,
# point phones at a file of its choosing, or send everyone back to an older release.
KEYS=${KEYS:-./obsidian-keys}
[ -f "$KEYS/releasekey.pk8" ] || { echo "      no $KEYS/releasekey.pk8 to sign the metadata with"; exit 1; }
SIG=$(mktemp)
openssl dgst -sha256 -sign "$KEYS/releasekey.pk8" -keyform DER -out "$SIG" "$TMP"
# Prove the signature verifies before it goes anywhere: a phone that cannot check it will refuse
# the update entirely, and the failure would only show up on someone else's phone.
openssl dgst -sha256 -verify <(openssl x509 -in "$KEYS/releasekey.x509.pem" -pubkey -noout) \
  -signature "$SIG" "$TMP" >/dev/null || { echo "      the signature does not verify, not publishing"; exit 1; }
echo "      signed, $(stat -c %s "$SIG") bytes"

# Both files are put in place with a move, which is atomic, but there is still a moment between the
# two moves when a phone could read one new file and one old one. It refuses the mismatch and tries
# again later, which is the right outcome; the window is a fraction of a second.
scp "${SSH_OPTS[@]}" -q "$SIG" "$SITE:$WEBROOT/updates/$DEVICE.json.sig.part"
scp "${SSH_OPTS[@]}" -q "$TMP" "$SITE:$WEBROOT/updates/$DEVICE.json.part"
ssh "${SSH_OPTS[@]}" "$SITE" "cd $WEBROOT/updates && mv $DEVICE.json.sig.part $DEVICE.json.sig && mv $DEVICE.json.part $DEVICE.json && sudo chmod 644 $DEVICE.json $DEVICE.json.sig"
rm -f "$TMP" "$SIG"

echo
echo "Published. Phones on $MODEL will see $VERSION the next time they check."
