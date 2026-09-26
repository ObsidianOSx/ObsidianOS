#!/usr/bin/env bash
# Turns the upstream source tree into OBSIDIAN: our two apps built in, the stock apps a private phone has
# no use for left out, and Obsidian Chat made the phone's device owner on first boot (which is what
# enforces location-off, the compulsory screen lock and the panic wipe).
#
# Run on the build server:
#   emulator:         bash ~/obsidian/obsidian-os-changes.sh
#   Pixel 8 (shiba):  adevtool generate-all -d shiba   (first - it regenerates the phone's product files)
#                     bash ~/obsidian/obsidian-os-changes.sh shiba
# Then rebuild: lunch sdk_phone64_x86_64-cur-userdebug (emulator) or shiba-cur-user (Pixel 8).
#
# Module names below were read from the image we actually built, not guessed.
# Left out: Dialer, AppStore, Messaging, Gallery2, Calendar, Contacts, DeskClock, Music,
#   ExactCalculator, PdfViewerGOS, ThemePicker, ThemesStub, TrichromeChrome, InfoApp, Auditor.
# Kept on purpose: Camera, LatinIME (the keyboard - without it there is no way to type),
#   Settings, SystemUI, Launcher3QuickStep, WallpaperCropper (SystemUI/Launcher use it for the
#   wallpaper), PermissionController, Photopicker (needed to attach photos in chat), and the
#   content providers. TrichromeWebView/VanadiumConfig stay too: that is the system WebView, and
#   removing it breaks every app that renders a web page, including parts of Settings. Only the
#   standalone Chrome browser (TrichromeChrome) goes, since Tor Browser is the browser here.
#
# Every file it edits is backed up under ~/obsidian/backups, mirroring its path in the tree.
set -eu

# Write or copy only when the content would actually change. Rewriting a file with identical
# content still moves its timestamp, and a touched Android.bp makes Soong redo its whole analysis,
# which costs about five hours on the build machine. This keeps repeat attempts cheap.
write_if_changed() {
  dest=$1
  tmp=$(mktemp)
  cat > "$tmp"
  if [ -f "$dest" ] && cmp -s "$tmp" "$dest"; then rm -f "$tmp"; else mv "$tmp" "$dest"; fi
}
copy_if_changed() { cmp -s "$1" "$2" 2>/dev/null || cp "$1" "$2"; }

# Keep backups OUTSIDE the source tree. Android compiles every file inside a resource
# folder, and a name like default_workspace_5x5.xml.before-obsidian is not a valid
# resource name, so a backup left beside the original breaks the build 31% into a compile.
BACKUPS=${OBSIDIAN_BACKUPS:-$HOME/obsidian/backups}
backup_file() {
  rel=${1#"$OS/"}
  dest=$BACKUPS/$rel
  [ -f "$dest" ] && return 0
  mkdir -p "$(dirname "$dest")"
  cp -p "$1" "$dest"
}

OS=${OS_TREE:-$HOME/os}
V="$OS/vendor/obsidian"

# A few strings the user sees name the upstream project, and steps 7 and 8 replace them. The name is
# read from a private file beside this script rather than written here, so the public source carries
# no upstream branding. That file is gitignored; create it with a single line: UPSTREAM_NAME=<name>
HERE=$(cd "$(dirname "$0")" && pwd)
if [ -f "$HERE/upstream.env" ]; then . "$HERE/upstream.env"; fi
UPSTREAM_NAME=${UPSTREAM_NAME:?"set UPSTREAM_NAME in $HERE/upstream.env"}
UPSTREAM_LC=$(printf '%s' "$UPSTREAM_NAME" | tr '[:upper:]' '[:lower:]')
APKS="$HOME/obsidian/apks"
PRODUCT="$OS/build/make/target/product"
EMULATOR_MK="$OS/device/generic/goldfish/product/phone.mk"
TARGET=${1:-emulator}   # "emulator", or a phone codename such as shiba (Pixel 8)
# Tor Browser is built per CPU: x86_64 for the emulator, aarch64 for phones
if [ "$TARGET" = emulator ]; then TOR_APK="$APKS/TorBrowser.apk"; else TOR_APK="$APKS/TorBrowser-aarch64.apk"; fi

# PROVEN ON DEVICE 2026-09-17, do not "simplify" these back:
#  * The Tor Browser payload must NOT be named .apk: the build system rejects any .apk in
#    PRODUCT_COPY_FILES ("use BUILD_PREBUILT instead"). Android installs happily from a file
#    with any name, verified with `pm install` on a device, so it ships as TorBrowser.payload.
#  * Tor Browser must NOT be a system app. Gecko cannot find libmozglue.so when the APK sits in
#    /product/app with compressed libraries ("Could not find mozglue path"), and it cannot be
#    replaced at runtime either (fs-verity is required to update a system package). It is shipped
#    as a plain file and installed at first boot, which also preserves its official signature.
#  * The init service that provisions device owner was blocked by SELinux ("no domain transition
#    from u:r:init:s0") while it ran as root in init's domain. It now runs /system/bin/sh with
#    seclabel u:r:shell:s0, the domain adb shell uses, which needs no new policy. Verified on a
#    Pixel 8: started at boot, exited 0, and restored three-button navigation by itself.
#  * The home screen dock comes from Launcher3's default_workspace_*.xml (container -101 is the
#    hotseat), which hardcodes Dialer/Messaging/Browser/Camera. Two of those no longer exist here,
#    which is why the dock looked half empty. Step 6 replaces them with our three apps.
#
# SWAPPING ObsidianChat.apk ON A RUNNING EMULATOR (for testing a new build without a 5h rebuild):
#   It is a system app, so `adb install` is refused (fs-verity) and the file has to be replaced on
#   /product via an overlay. The overlay scratch only has room for ONE 65 MB copy, so a second push
#   on the same boot fails with "No space left on device" AND DELETES THE APK, leaving the app
#   crashing on launch. The sequence that works, once per boot:
#     emulator -avd obsidian-os -wipe-data -writable-system ...   (clears the overlay)
#     adb root && adb remount && adb reboot                       (remount needs the reboot!)
#     adb root && adb push <apk> /product/app/ObsidianChat/ObsidianChat.apk
#     adb reboot
#   Verify with `stat -c %y` on the APK, never by adb push's "1 file pushed" line, which prints
#   even when the copy failed. After any -wipe-data the overlay is gone and the ORIGINAL apk is
#   back, along with a reset of device owner, PIN, nav mode and location.

echo "== 1. our apps as prebuilts"
for f in "$APKS/ObsidianChat.apk" "$TOR_APK"; do
  [ -f "$f" ] || { echo "missing $f"; exit 1; }
done
mkdir -p "$V/prebuilt/ObsidianChat" "$V/provision" "$V/install"
copy_if_changed "$APKS/ObsidianChat.apk" "$V/prebuilt/ObsidianChat/ObsidianChat.apk"
# Tor Browser is a payload file, not a build module - see the note at the top of this script.
copy_if_changed "$TOR_APK" "$V/install/TorBrowser.payload"
# Cake Wallet, for Monero. Same treatment as Tor Browser and for the same reason: it is installed
# at first boot rather than built in, which also keeps the developer's own signature on it.
[ -f "$APKS/CakeWallet-arm64.apk" ] && copy_if_changed "$APKS/CakeWallet-arm64.apk" "$V/install/CakeWallet.payload"
rm -rf "$V/prebuilt/TorBrowser"
rm -f "$V/install/TorBrowser.apk"   # stale name from before the payload rename

write_if_changed "$V/prebuilt/ObsidianChat/Android.bp" <<'EOF'
// preprocessed: mandatory for presigned APKs targeting SDK 30+. They carry a v2 signature over
// the whole archive, so any re-alignment or re-signing by the build system destroys it.
// Deliberately NOT privileged: device owner is granted at runtime by dpm and needs no privileged
// status, while a privileged app signed with a non-platform key requires a privapp-permissions
// allowlist entry or the system server refuses to boot.
android_app_import {
    name: "ObsidianChat",
    apk: "ObsidianChat.apk",
    presigned: true,
    preprocessed: true,
    product_specific: true,
    dex_preopt: { enabled: false },
}
EOF

echo "== 2. device owner from first boot"
write_if_changed "$V/provision/obsidian-provision.sh" <<'EOF'
#!/system/bin/sh
# Runs at every boot. Each step checks whether it is already done rather than recording a flag,
# because a custom property would need its own type in property_contexts and permission to set it;
# asking the system what it already looks like avoids that entirely.

# Tor Browser is installed here rather than bundled into the image: Gecko cannot find its
# compressed native libraries when the APK lives on a read-only system partition, and a system
# app cannot be replaced later without fs-verity. Installing also keeps its official signature.
if [ -f /product/obsidian/TorBrowser.payload ] && ! pm path org.torproject.torbrowser >/dev/null 2>&1; then
  pm install -t /product/obsidian/TorBrowser.payload
fi

# Cake Wallet, installed the same way and for the same reasons as Tor Browser above.
if [ -f /product/obsidian/CakeWallet.payload ] && ! pm path com.cakewallet.cake_wallet >/dev/null 2>&1; then
  pm install -t /product/obsidian/CakeWallet.payload
  # It asks for location and bluetooth. This phone holds location off for everything anyway, but
  # denying them outright means the permission screen tells the truth about what it has. The
  # camera is left alone: it needs that to read an address from a QR code.
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_SCAN BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE; do
    pm revoke com.cakewallet.cake_wallet "android.permission.$perm" 2>/dev/null || true
  done
fi

# Three-button navigation instead of gestures: a visible Back button is self-evident, and on a
# phone with three apps and no dialler, nobody should be stuck in Settings hunting for an edge swipe.
cmd overlay enable-exclusive com.android.internal.systemui.navbar.threebutton

# Device owner: holds location off, requires a screen lock, and enables the panic wipe. It can only
# be claimed while the phone is still unprovisioned, so this has to land on the first boot after a
# wipe, before anyone finishes the setup screens.
if ! dumpsys device_policy 2>/dev/null | grep -q "admin=ComponentInfo{obsidian.chat"; then
  cmd device_policy set-device-owner obsidian.chat/obsidian.chat.security.PanicDeviceAdmin
fi
EOF
chmod 755 "$V/provision/obsidian-provision.sh"

write_if_changed "$V/provision/obsidian-provision.rc" <<'EOF'
# Runs in the shell domain, which is the one adb shell itself uses and therefore the one pm, cmd
# and device_policy already expect to be called from. Starting /system/bin/sh and passing the
# script, rather than executing the script directly, is what makes that transition legal, and is
# the same shape as AOSP's own console service. The previous version ran as root in u:r:init:s0,
# where SELinux refuses the binder calls these commands need, so it failed silently on every boot.
service obsidian_provision /system/bin/sh /product/bin/obsidian-provision.sh
    class late_start
    user shell
    group shell
    seclabel u:r:shell:s0
    oneshot
    disabled

on property:sys.boot_completed=1
    start obsidian_provision
EOF

echo "== 3. the OBSIDIAN product pieces"
# The phone has to be able to say which release it is running, or it cannot tell whether an update
# is newer than itself, and nobody can tell what a phone in front of them has on it. The version is
# decided when the build starts and read back out of the image afterwards, so the name on the
# release and the name inside it can never drift apart.
OBSIDIAN_VERSION=${OBSIDIAN_VERSION:-$(date -u +%Y.%m.%d)}
sed -e "s|@VERSION@|$OBSIDIAN_VERSION|g" -e "s|@DEVICE@|$TARGET|g" <<'EOF' | write_if_changed "$V/obsidian.mk"
PRODUCT_PACKAGES += \
    ObsidianChat

# What this phone is running, in the form people see it written down.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.obsidian.version=@VERSION@ \
    ro.obsidian.device=@DEVICE@

# These go on the product partition, NOT system: the generic system image has an enforced
# artifact path requirement, so any extra file under system/ fails the build outright.
# ObsidianChat is product_specific for the same reason. init reads /product/etc/init too.
# TorBrowser ships as a plain payload and is installed at first boot, NOT built in - Gecko
# cannot load compressed libraries from a read-only system partition.
PRODUCT_COPY_FILES += \
    vendor/obsidian/install/TorBrowser.payload:$(TARGET_COPY_OUT_PRODUCT)/obsidian/TorBrowser.payload \
    vendor/obsidian/install/CakeWallet.payload:$(TARGET_COPY_OUT_PRODUCT)/obsidian/CakeWallet.payload \
    vendor/obsidian/provision/obsidian-provision.sh:$(TARGET_COPY_OUT_PRODUCT)/bin/obsidian-provision.sh \
    vendor/obsidian/provision/obsidian-provision.rc:$(TARGET_COPY_OUT_PRODUCT)/etc/init/obsidian-provision.rc
EOF

echo "== 4. leave out the apps a private phone has no use for"
drop() { # drop <module> <makefile>
  local app=$1 mk=$2
  [ -f "$mk" ] || return 0
  backup_file "$mk"
  if grep -qE "^[[:space:]]+$app[[:space:]]*\\\\?$" "$mk"; then
    sed -i -E "/^[[:space:]]+$app[[:space:]]*\\\\?$/d" "$mk"
    echo "  dropped $app from $(basename "$mk")"
  fi
}

for app in AppStore Calendar Contacts DeskClock ExactCalculator Gallery2 Music PdfViewerGOS \
           ThemePicker ThemesStub TrichromeChrome InfoApp Auditor; do
  drop "$app" "$PRODUCT/handheld_product.mk"
done
drop Dialer "$PRODUCT/telephony_product.mk"
drop Messaging "$PRODUCT/aosp_product.mk"
drop Messaging "$PRODUCT/aosp_base_telephony.mk"
for app in BasicDreams PhotoTable EasterEgg; do
  drop "$app" "$PRODUCT/handheld_system.mk"
  drop "$app" "$PRODUCT/handheld_system_ext.mk"
done

echo "== 6. our three apps in the launcher dock"
# The stock hotseat hardcodes Dialer/Messaging/Browser/Camera; the first two no longer exist on
# this phone, so the dock came up half empty. Container -101 is the hotseat, screen = position.
LAUNCHER_XML="$OS/packages/apps/Launcher3/res/xml"
for layout in "$LAUNCHER_XML"/default_workspace_*.xml; do
  [ -f "$layout" ] || continue
  backup_file "$layout"
  write_if_changed "$layout" <<'WS'
<?xml version="1.0" encoding="utf-8"?>
<!-- OBSIDIAN: the phone has three apps. Chat sits centre, Tor and Camera either side. -->
<favorites xmlns:launcher="http://schemas.android.com/apk/res-auto/com.android.launcher3">
    <favorite
        launcher:container="-101"
        launcher:screen="0"
        launcher:x="0"
        launcher:y="0"
        launcher:packageName="org.torproject.torbrowser"
        launcher:className="org.torproject.torbrowser.App" />
    <favorite
        launcher:container="-101"
        launcher:screen="1"
        launcher:x="1"
        launcher:y="0"
        launcher:packageName="obsidian.chat"
        launcher:className="obsidian.chat.MainActivity" />
    <!-- The camera is found by what it does, not by package name, so any camera app fits -->
    <resolve
        launcher:container="-101"
        launcher:screen="2"
        launcher:x="2"
        launcher:y="0" >
        <favorite launcher:uri="#Intent;action=android.media.action.STILL_IMAGE_CAMERA;end" />
        <favorite launcher:uri="#Intent;action=android.intent.action.CAMERA_BUTTON;end" />
    </resolve>
</favorites>
WS
  echo "  dock set in $(basename "$layout")"
done

echo "== 5. include OBSIDIAN in the product being built ($TARGET)"
# Emulator: goldfish's phone.mk. A phone: the product makefile adevtool generates, which
# `adevtool generate-all` rewrites - so for a phone this script must run after it, every time.
if [ "$TARGET" = emulator ]; then
  PRODUCT_MK="$EMULATOR_MK"
else
  PRODUCT_MK="$OS/vendor/google_devices/$TARGET/$TARGET.mk"
fi
[ -f "$PRODUCT_MK" ] || { echo "  missing $PRODUCT_MK - run adevtool generate-all -d $TARGET first"; exit 1; }
if ! grep -q "vendor/obsidian/obsidian.mk" "$PRODUCT_MK"; then
  backup_file "$PRODUCT_MK"
  printf '\n$(call inherit-product-if-exists, vendor/obsidian/obsidian.mk)\n' >> "$PRODUCT_MK"
  echo "  added to $PRODUCT_MK"
fi

echo "== 7. branding: nothing the user sees should name the upstream project"
# The framework hardcodes its own label in its manifest rather than in a string resource, and that
# label is what the notification shade shows for every system notification, for example
# "<upstream name> - Serial console enabled" on a test build. Found with: aapt2 dump badging framework-res.apk
FW_MANIFEST="$OS/frameworks/base/core/res/AndroidManifest.xml"
if grep -q "android:label=\"$UPSTREAM_NAME\"" "$FW_MANIFEST" 2>/dev/null; then
  backup_file "$FW_MANIFEST"
  sed -i "s/android:label=\"$UPSTREAM_NAME\"/android:label=\"OBSIDIAN\"/" "$FW_MANIFEST"
  echo "  system notifications now show OBSIDIAN"
else
  echo "  framework label already carries no upstream name"
fi

echo "== 8. the setup wizard: the first thing anyone sees on a new phone"
# It greets you by the upstream name and shows that project's logo. Six user-visible strings across
# the whole tree carry the name; these are them. Links to the upstream website are left alone where
# they point at real services, and replaced where they are ours to own.
SW="$OS/packages/apps/SetupWizard2/res/values/strings.xml"
if [ -f "$SW" ] && grep -q "$UPSTREAM_NAME" "$SW"; then
  backup_file "$SW"
  sed -i "s/$UPSTREAM_NAME/OBSIDIAN/g" "$SW"
  sed -i "s#>$UPSTREAM_LC\\.org/UBL<#>github.com/ObsidianOSx/ObsidianOS<#" "$SW"
  # Their tagline carries no product name, so the rename above does not touch it.
  sed -i "s#<string name=\"${UPSTREAM_LC}_desc\">[^<]*</string>#<string name=\"${UPSTREAM_LC}_desc\">Three apps. Everything encrypted. Nothing to sell.</string>#" "$SW"
  echo "  setup wizard now welcomes you to OBSIDIAN ($(grep -c OBSIDIAN "$SW") strings)"
fi
# The welcome and finish screens show a logo. Keep the file name, since layouts reference it,
# and replace the artwork with the OBSIDIAN mark: a single thin O.
SW_ICON="$OS/packages/apps/SetupWizard2/res/drawable/${UPSTREAM_LC}_icon.xml"
if [ -f "$SW_ICON" ] && ! grep -q "OBSIDIAN mark" "$SW_ICON"; then
  backup_file "$SW_ICON"
  write_if_changed "$SW_ICON" <<'ICON'
<!-- OBSIDIAN mark: a single thin O. File name kept because the layouts reference it. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="253.8dp"
    android:height="253.8dp"
    android:tint="?android:attr/textColorPrimary"
    android:viewportWidth="256"
    android:viewportHeight="256">
    <path
        android:fillColor="#00000000"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="6"
        android:pathData="M128,24 C167,24 198,70 198,128 C198,186 167,232 128,232 C89,232 58,186 58,128 C58,70 89,24 128,24 Z" />
</vector>
ICON
  echo "  welcome screen logo replaced with the OBSIDIAN mark"
fi
# One stray mention in the framework's own strings, in a message about old 32-bit apps.
FW_STRINGS="$OS/frameworks/base/core/res/res/values/strings.xml"
if [ -f "$FW_STRINGS" ] && grep -q "$UPSTREAM_NAME" "$FW_STRINGS"; then
  backup_file "$FW_STRINGS"
  sed -i "s/$UPSTREAM_NAME/OBSIDIAN/g" "$FW_STRINGS"
  echo "  framework strings cleared"
fi
# The recovery program titles its own screens, and everyone who installs sees one of them: the
# installer restarts the phone into fastbootd to write the system partitions. Only these two titles
# carry the name; the rest of recovery's text is generic.
for RC_SRC in "$OS/bootable/recovery/fastboot/fastboot.cpp" "$OS/bootable/recovery/recovery.cpp"; do
  if [ -f "$RC_SRC" ] && grep -q "\"$UPSTREAM_NAME \(Fastboot\|Recovery\)\"" "$RC_SRC"; then
    backup_file "$RC_SRC"
    sed -i "s/\"$UPSTREAM_NAME \(Fastboot\|Recovery\)\"/\"OBSIDIAN \1\"/" "$RC_SRC"
    echo "  $(basename "$RC_SRC") now titles its screen OBSIDIAN"
  fi
done

echo "== 9. location off from the very first boot"
# Stock Android starts at 3, meaning location on and accurate. That leaves a window on a brand new
# phone: location is live from first boot until the chat app becomes device owner and turns it off.
# Starting at 0 closes it. The device owner restrictions then stop anyone turning it back on.
LOC_DEFAULTS="$OS/frameworks/base/packages/SettingsProvider/res/values/defaults.xml"
if [ -f "$LOC_DEFAULTS" ] && grep -q '<integer name="def_location_mode">3</integer>' "$LOC_DEFAULTS"; then
  backup_file "$LOC_DEFAULTS"
  sed -i 's#<integer name="def_location_mode">3</integer>#<integer name="def_location_mode">0</integer>#' "$LOC_DEFAULTS"
  echo "  location now starts switched off"
else
  echo "  location default already off"
fi

echo
echo "Done for $TARGET."
echo "Release signing keys are still to be generated offline; these prebuilts keep their own signatures."
