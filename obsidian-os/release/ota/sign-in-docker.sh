#!/bin/bash
# Runs the signing inside a throwaway Linux container, because the AOSP signing tools do not run on
# Windows and the keys must not leave this machine.
#
#   sign-in-docker.sh <device> <version> <unsigned-target-files.zip>
#
# The keys are mounted into the container read only and nothing is ever copied into the image, so
# the container can be deleted without losing anything and cannot carry keys anywhere.
#
# Environment: the same as sign-release.sh, plus
#   IMAGE      name for the small image this builds once   (default obsidian-signing)
set -euo pipefail

DEVICE=${1:?"give the device codename"}
VERSION=${2:?"give the version"}
TARGET_FILES=${3:?"give the unsigned target-files zip"}

KEYS=${KEYS:-$HOME/obsidian-keys}
OTATOOLS=${OTATOOLS:?"set OTATOOLS to the otatools.zip that came with this build"}
OUT=${OUT:-./signed/$DEVICE-$VERSION}
PREVIOUS=${PREVIOUS:-}
IMAGE=${IMAGE:-obsidian-signing}
HERE=$(cd "$(dirname "$0")" && pwd)

docker version >/dev/null 2>&1 || { echo "Docker is not running. Start Docker Desktop and try again."; exit 1; }

# Built once and reused. Everything in it comes from Ubuntu's own packages: Java runs signapk,
# Python runs the release tools, and the rest is unpacking zips.
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "[setup] building the signing container, once"
  docker build -q -t "$IMAGE" - <<'DOCKERFILE' | sed 's/^/        /'
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-protobuf openjdk-21-jre-headless zip unzip openssl file \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work
DOCKERFILE
fi

mkdir -p "$OUT"
abs() { (cd "$(dirname "$1")" && printf '%s/%s' "$(pwd)" "$(basename "$1")"); }

# Everything the run needs, each mounted where the script expects it. Keys read only.
args=(
  --rm
  -v "$(abs "$KEYS"):/keys:ro"
  -v "$(abs "$TARGET_FILES"):/in/target_files.zip:ro"
  -v "$(abs "$OTATOOLS"):/in/otatools.zip:ro"
  -v "$(abs "$OUT"):/out"
  -v "$HERE/sign-release.sh:/work/sign-release.sh:ro"
  -e "KEYS=/keys" -e "OTATOOLS=/in/otatools.zip" -e "OUT=/out"
)
if [ -n "$PREVIOUS" ]; then
  args+=(-v "$(abs "$PREVIOUS"):/in/previous.zip:ro" -e "PREVIOUS=/in/previous.zip")
fi

echo "[run] signing $DEVICE $VERSION inside the container"
docker run "${args[@]}" "$IMAGE" bash /work/sign-release.sh "$DEVICE" "$VERSION" /in/target_files.zip

echo
echo "Signed on this machine. The container is gone; the keys never left $KEYS."
