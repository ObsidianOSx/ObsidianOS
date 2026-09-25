#!/bin/bash
# Signs a build from Windows, by doing the work inside WSL where the AOSP tools actually run.
#
#   sign-in-wsl.sh <device> <version> <unsigned-target-files.zip>
#
# The keys stay on the Windows side and are read through /mnt/c. Nothing copies them into Linux.
# The build itself is copied in, because unpacking a 3.8 GB zip across the Windows mount is far
# slower than doing it on the Linux filesystem.
#
# Environment:
#   DISTRO     the WSL distribution to use            (default Ubuntu)
#   KEYS       Windows path to the keys               (default C:/Users/<you>/obsidian-keys)
#   PREVIOUS   Windows path to the previous signed target-files, for a small update
#   OUT        Windows path for the results           (default ./signed/<device>-<version>)
set -euo pipefail

DEVICE=${1:?"give the device codename"}
VERSION=${2:?"give the version"}
TARGET_FILES=${3:?"give the unsigned target-files zip"}
DISTRO=${DISTRO:-Ubuntu}
KEYS=${KEYS:-C:/Users/$USERNAME/obsidian-keys}
OUT=${OUT:-./signed/$DEVICE-$VERSION}
PREVIOUS=${PREVIOUS:-}
HERE=$(cd "$(dirname "$0")" && pwd)

wsl.exe -d "$DISTRO" -e true >/dev/null 2>&1 || {
  echo "WSL distribution '$DISTRO' is not there. Install it with: wsl --install -d Ubuntu"; exit 1; }

# Turn a Windows path into the one Linux sees through the mount.
tolinux() { printf '%s' "$1" | sed -e 's|\\|/|g' -e 's|^\([A-Za-z]\):|/mnt/\L\1|'; }

echo "[1/4] making sure the signing tools are there"
# python-is-python3 matters: the AOSP release tools run themselves with "python", which Ubuntu
# stopped providing by default, and the failure is an unhelpful "env: 'python': No such file".
wsl.exe -d "$DISTRO" -u root -e bash -c '
  need=""
  for p in python3 python-is-python3 openjdk-21-jre-headless zip unzip openssl; do
    dpkg -s "$p" >/dev/null 2>&1 || need="$need $p"
  done
  if [ -n "$need" ]; then
    echo "      installing:$need"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq >/dev/null 2>&1
    apt-get install -y -qq $need >/dev/null 2>&1
  fi
  command -v python >/dev/null || { echo "      python is still missing"; exit 1; }
'

WORK=/root/signing/$DEVICE-$VERSION
echo "[2/4] copying the build into Linux"
wsl.exe -d "$DISTRO" -u root -e bash -c "mkdir -p $WORK && cp '$(tolinux "$TARGET_FILES")' $WORK/target_files.zip && cp '$(tolinux "${OTATOOLS:?set OTATOOLS to the otatools.zip from this build}")' $WORK/otatools.zip"

echo "[3/4] signing"
prev_env=""
if [ -n "$PREVIOUS" ]; then
  wsl.exe -d "$DISTRO" -u root -e bash -c "cp '$(tolinux "$PREVIOUS")' $WORK/previous.zip"
  prev_env="PREVIOUS=$WORK/previous.zip"
fi
wsl.exe -d "$DISTRO" -u root -e bash -c "
  KEYS='$(tolinux "$KEYS")' OTATOOLS=$WORK/otatools.zip OUT=$WORK/out $prev_env \
  bash '$(tolinux "$HERE")/sign-release.sh' $DEVICE $VERSION $WORK/target_files.zip"

echo "[4/4] bringing the results back to Windows"
mkdir -p "$OUT"
wsl.exe -d "$DISTRO" -u root -e bash -c "cp $WORK/out/* '$(tolinux "$(cd "$OUT" && pwd)")'/"
ls -l "$OUT" | awk 'NR>1 {printf "      %12s  %s\n", $5, $9}'

echo
echo "Signed. The keys were read from $KEYS and never copied into Linux."
echo "The working copy is still at $WORK inside WSL; delete it when you no longer need it."
