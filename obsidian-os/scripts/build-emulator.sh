#!/bin/bash
# OBSIDIAN OS: build the x86_64 emulator image for testing without a phone.
# Usage: build-emulator.sh [VARIANT]   (default userdebug)
# Output: out/target/product/*/sdk-repo-*system-images*.zip, which installs into the Android
# SDK's system-images directory on any host (Windows included).
set -o pipefail

VARIANT=${1:-userdebug}

cd ~/os || exit 1
source build/envsetup.sh
set -e

lunch "sdk_phone64_x86_64-cur-$VARIANT"

# 4 vCPUs / 16 GB RAM: low parallelism so linking fits in RAM + swap
m -j5
m -j5 emu_img_zip

ls -la out/target/product/*/sdk-repo-*system-images*.zip
echo "BUILD OK: sdk_phone64_x86_64-cur-$VARIANT"
