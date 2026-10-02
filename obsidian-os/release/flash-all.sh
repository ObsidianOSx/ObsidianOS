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

# The firmware goes onto both slots, not just the one in use. A phone keeps two copies of the
# system and can fall back to the other one, but only if that copy's firmware matches. Writing one
# slot leaves the other holding firmware from whatever was on the phone before, and a phone that
# then tries the old slot fails to start and marks it dead.
echo "Firmware, both slots"
fastboot flash --slot=other bootloader @BOOTLOADER@
fastboot --set-active=other
fastboot reboot-bootloader
sleep 5
fastboot flash --slot=other bootloader @BOOTLOADER@
fastboot --set-active=other
fastboot reboot-bootloader
sleep 5
fastboot flash radio @RADIO@
fastboot reboot-bootloader
sleep 5

# Three partitions hold firmware signed separately from the system, and they keep whatever the
# previous operating system put there. Left alone they can disagree with the system being installed
# and stop the phone starting, so they are cleared and the phone rebuilds them. None of this is
# needed to install, and none of it is harmful: clearing them is what Google's own installer does.
# Each one is allowed to fail, because which of them exists differs between phones.
echo "Clearing firmware left by the previous system"
fastboot erase avb_custom_key || true   # a key from a signed build would not match this one
fastboot oem uart disable || true
fastboot erase fips || true
fastboot erase dpm_a || true
fastboot erase dpm_b || true

# The system itself. This is the long part, and the one that goes wrong: it sends about two
# gigabytes in chunks, and on Windows the USB connection to this phone sometimes drops partway
# through. A drop leaves the phone with half a system and nothing to start, which looks alarming
# and is not: writing it again from the beginning fixes it, so that is what happens here.
echo "The system, about two gigabytes"
attempt=1
while [ "$attempt" -le 3 ]; do
  if fastboot -w update @IMAGE@; then
    echo "Done. The phone is starting OBSIDIAN. The first start takes a few minutes."
    exit 0
  fi
  if [ "$attempt" -lt 3 ]; then
    echo
    echo "That transfer failed partway through, which is usually the USB connection rather than"
    echo "the phone. Attempt $((attempt + 1)) of 3, starting again from the beginning."
    echo "If it keeps failing: use the cable that came with the phone, plug straight into the"
    echo "computer rather than a hub or a monitor, and try a different port."
    echo
    fastboot reboot-bootloader || true
    sleep 5
  fi
  attempt=$((attempt + 1))
done

echo
echo "The system could not be written after three attempts, so the phone has no working system on"
echo "it at the moment. It is not broken and nothing is permanent. Hold the power button for ten"
echo "seconds to turn it off, then hold volume down and press power to reach Fastboot Mode again,"
echo "and run this script once more. A different USB cable or port fixes this most of the time."
exit 1
