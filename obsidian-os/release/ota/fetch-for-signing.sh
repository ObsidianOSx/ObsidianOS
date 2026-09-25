#!/bin/bash
# Brings a finished build down from the build server so it can be signed here, where the keys are.
#
#   fetch-for-signing.sh <device> [destination]
#
# Two things come down: the target-files, which is the build in the form the signing tools want,
# and otatools, which is the signing tools themselves. They have to come from the same build, since
# a mismatch between the tools and the tree they came from fails in confusing ways.
#
# Nothing secret travels in this direction. The keys stay here and never go the other way.
set -euo pipefail

DEVICE=${1:?"give the device codename, for example shiba"}
DEST=${2:-./to-sign/$DEVICE}
SERVER=${SERVER:?"set SERVER to user@host of the build server"}
SSH_KEY=${SSH_KEY:-}
SSH_OPTS=(-o ConnectTimeout=25 -o ServerAliveInterval=30)
[ -n "$SSH_KEY" ] && SSH_OPTS+=(-i "$SSH_KEY")

TF="os/out/target/product/$DEVICE/obj/PACKAGING/target_files_intermediates/$DEVICE-target_files.zip"
# The otatools package has no .zip on the end and sits under a long intermediates path. It is a
# zip all the same, and it is the only copy the build produces.
OT="os/out/host/linux-x86/obj/ETC/otatools-packagelinux_glibc_x86_64_intermediates/otatools-packagelinux_glibc_x86_64"

echo "[1/4] checking the build server has what we need"
ssh "${SSH_OPTS[@]}" "$SERVER" "test -f ~/$TF" || {
  echo "no target-files for $DEVICE on the server. Build it first."; exit 1; }
# otatools is not built by default, and it is quick compared to a build, so just make sure it exists.
ssh "${SSH_OPTS[@]}" "$SERVER" "test -f ~/$OT" || {
  echo "      otatools.zip is missing, building it now (a few minutes)"
  ssh "${SSH_OPTS[@]}" "$SERVER" 'cd ~/os && source build/envsetup.sh >/dev/null && lunch '"$DEVICE"'-cur-userdebug >/dev/null && m otatools-package' \
    | tail -3 | sed 's/^/      /'
}

mkdir -p "$DEST"
echo "[2/4] fetching the build, about 3.6 GB"
scp "${SSH_OPTS[@]}" -q "$SERVER:$TF" "$DEST/$DEVICE-target_files.zip"
echo "[3/4] fetching the signing tools"
scp "${SSH_OPTS[@]}" -q "$SERVER:$OT" "$DEST/otatools.zip"

echo "[4/4] checking both arrived intact"
for f in "$DEST/$DEVICE-target_files.zip" "$DEST/otatools.zip"; do
  remote=$(basename "$f")
  case "$remote" in
    otatools.zip) rpath="$OT" ;;
    *)            rpath="$TF" ;;
  esac
  want=$(ssh "${SSH_OPTS[@]}" "$SERVER" "sha256sum ~/$rpath | cut -d' ' -f1")
  got=$(sha256sum "$f" | cut -d' ' -f1)
  [ "$want" = "$got" ] || { echo "      $remote does not match the server copy"; exit 1; }
  printf '      %-28s %12s bytes, sha256 matches\n' "$remote" "$(stat -c %s "$f")"
done

echo
echo "Ready to sign:"
echo "  KEYS=./obsidian-keys OTATOOLS=$DEST/otatools.zip \\"
echo "    ./sign-release.sh $DEVICE <version> $DEST/$DEVICE-target_files.zip"
