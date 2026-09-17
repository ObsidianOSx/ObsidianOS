#!/bin/bash
# OBSIDIAN OS: build one device from the synced tree at ~/os.
# Usage: build-baseline.sh DEVICE [VARIANT]   e.g. build-baseline.sh shiba user
# Extracts vendor files with adevtool, then builds the target-files and otatools packages
# that script/generate-release.sh needs for signing.
set -o pipefail

DEVICE=${1:?usage: build-baseline.sh DEVICE [VARIANT]}
VARIANT=${2:-user}

cd ~/os || exit 1
source build/envsetup.sh
set -e

yarn --cwd vendor/adevtool/ install --frozen-lockfile
# envsetup.sh only defines `adevtool` as an alias, which scripts don't expand
vendor/adevtool/bin/run generate-all -d "$DEVICE"

lunch "$DEVICE-cur-$VARIANT"

# Extra boot image targets per the upstream build documentation
case "$DEVICE" in
    oriole|raven|bluejay)
        targets=(vendorbootimage target-files-package) ;;
    panther|cheetah|lynx|tangorpro|felix|shiba|husky|akita|tokay|caiman|komodo|comet)
        targets=(vendorbootimage vendorkernelbootimage target-files-package) ;;
    *)
        targets=(target-files-package) ;;
esac

# 4 vCPUs / 16 GB RAM: low parallelism so linking fits in RAM + swap
m -j5 "${targets[@]}"
m -j5 otatools-package
echo "BUILD OK: $DEVICE-cur-$VARIANT"
