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
# Every file it edits is backed up alongside the original with a .before-obsidian suffix.
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

OS=${OS_TREE:-$HOME/os}
V="$OS/vendor/obsidian"
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
#  * The init service that provisions device owner is blocked by SELinux ("no domain transition
#    from u:r:init:s0"). Until a vendor sepolicy domain exists, device owner must be set over adb.
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
rm -rf "$V/prebuilt/TorBrowser"

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
# Runs once at first boot.
if [ "$(getprop obsidian.provisioned)" = "1" ]; then exit 0; fi
# Tor Browser is installed here rather than bundled into the image: Gecko cannot find its
# compressed native libraries when the APK lives on a read-only system partition, and a system
# app cannot be replaced later without fs-verity. Installing also keeps its official signature.
if [ -f /product/obsidian/TorBrowser.payload ]; then
  pm install -t /product/obsidian/TorBrowser.payload
fi
# Three-button navigation instead of gestures: a visible Back button is self-evident, and on a
# phone with three apps and no dialler, nobody should be stuck in Settings hunting for an edge swipe.
cmd overlay enable-exclusive com.android.internal.systemui.navbar.threebutton

# Device owner: holds location off, requires a screen lock, and enables the panic wipe.
cmd device_policy set-device-owner obsidian.chat/obsidian.chat.security.PanicDeviceAdmin && \
  setprop obsidian.provisioned 1
EOF
chmod 755 "$V/provision/obsidian-provision.sh"

write_if_changed "$V/provision/obsidian-provision.rc" <<'EOF'
service obsidian_provision /product/bin/obsidian-provision.sh
    class late_start
    user root
    group root
    oneshot
    disabled

on property:sys.boot_completed=1
    start obsidian_provision
EOF

echo "== 3. the OBSIDIAN product pieces"
write_if_changed "$V/obsidian.mk" <<'EOF'
PRODUCT_PACKAGES += \
    ObsidianChat

# These go on the product partition, NOT system: the generic system image has an enforced
# artifact path requirement, so any extra file under system/ fails the build outright.
# ObsidianChat is product_specific for the same reason. init reads /product/etc/init too.
# TorBrowser ships as a plain payload and is installed at first boot, NOT built in - Gecko
# cannot load compressed libraries from a read-only system partition.
PRODUCT_COPY_FILES += \
    vendor/obsidian/install/TorBrowser.payload:$(TARGET_COPY_OUT_PRODUCT)/obsidian/TorBrowser.payload \
    vendor/obsidian/provision/obsidian-provision.sh:$(TARGET_COPY_OUT_PRODUCT)/bin/obsidian-provision.sh \
    vendor/obsidian/provision/obsidian-provision.rc:$(TARGET_COPY_OUT_PRODUCT)/etc/init/obsidian-provision.rc
EOF

echo "== 4. leave out the apps a private phone has no use for"
drop() { # drop <module> <makefile>
  local app=$1 mk=$2
  [ -f "$mk" ] || return 0
  [ -f "$mk.before-obsidian" ] || cp -p "$mk" "$mk.before-obsidian"
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
  [ -f "$layout.before-obsidian" ] || cp -p "$layout" "$layout.before-obsidian"
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
  [ -f "$PRODUCT_MK.before-obsidian" ] || cp -p "$PRODUCT_MK" "$PRODUCT_MK.before-obsidian"
  printf '\n$(call inherit-product-if-exists, vendor/obsidian/obsidian.mk)\n' >> "$PRODUCT_MK"
  echo "  added to $PRODUCT_MK"
fi

echo "== 7. branding: nothing the user sees should name the upstream project"
# The framework hardcodes its own label in its manifest rather than in a string resource, and that
# label is what the notification shade shows for every system notification, for example
# "UPSTREAM_NAME - Serial console enabled" on a test build. Found with: aapt2 dump badging framework-res.apk
FW_MANIFEST="$OS/frameworks/base/core/res/AndroidManifest.xml"
if grep -q 'android:label="UPSTREAM_NAME"' "$FW_MANIFEST" 2>/dev/null; then
  [ -f "$FW_MANIFEST.before-obsidian" ] || cp -p "$FW_MANIFEST" "$FW_MANIFEST.before-obsidian"
  sed -i 's/android:label="UPSTREAM_NAME"/android:label="OBSIDIAN"/' "$FW_MANIFEST"
  echo "  system notifications now show OBSIDIAN"
else
  echo "  framework label already carries no upstream name"
fi

echo
echo "Done for $TARGET."
echo "Release signing keys are still to be generated offline; these prebuilts keep their own signatures."
