#!/bin/bash
# Wait for the background repo sync to finish, then start the baseline build.
# Usage: after-sync.sh [DEVICE] [VARIANT]
DEVICE=${1:-shiba}
VARIANT=${2:-user}

while [ ! -f ~/os-sync.done ]; do
    sleep 60
done

if ! grep -qx 'exit=0' ~/os-sync.done; then
    echo "exit=sync-failed" > ~/build-"$DEVICE".done
    exit 1
fi

~/obsidian/build-baseline.sh "$DEVICE" "$VARIANT" > ~/build-"$DEVICE".log 2>&1
echo "exit=$?" > ~/build-"$DEVICE".done
