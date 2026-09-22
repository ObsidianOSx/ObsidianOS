#!/bin/sh
# Installs OBSIDIAN on a Google @MODEL@ that is in bootloader mode, and erases everything on it.
#
# Needs fastboot from Google's Android SDK Platform-Tools, on your PATH:
#   https://developer.android.com/tools/releases/platform-tools
# The bootloader must already be unlocked: fastboot flashing unlock, then confirm on the phone.
#
# This is a test build, signed with test keys. Leave the bootloader unlocked afterwards: a phone
# locked to an operating system it cannot verify refuses to start.
set -e
cd "$(dirname "$0")"

product=$(fastboot getvar product 2>&1 | sed -n 's/^product: *//p')
if [ "$product" != "@DEVICE@" ]; then
  echo "No @MODEL@ found in bootloader mode (found: ${product:-nothing}). Nothing was changed."
  exit 1
fi

fastboot flash bootloader @BOOTLOADER@
fastboot reboot-bootloader
sleep 5
fastboot flash radio @RADIO@
fastboot reboot-bootloader
sleep 5
fastboot -w update @IMAGE@

echo "Done. The phone is starting OBSIDIAN. The first start takes a few minutes."
