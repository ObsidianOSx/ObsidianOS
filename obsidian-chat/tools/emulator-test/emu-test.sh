#!/usr/bin/env bash
# Drives two Android emulators running Obsidian Chat. Runs in Git Bash on Windows; needs ANDROID_HOME.
#   emu-test.sh start                 launch obsidian-a (port 5554) and obsidian-b (5556), wait for boot
#   emu-test.sh install               install the debug APK on both
#   emu-test.sh launch <a|b>          open Obsidian Chat
#   emu-test.sh ui <a|b>              list on-screen text as "text | description | bounds"
#   emu-test.sh tap <a|b> <x> <y>     tap a point
#   emu-test.sh taptext <a|b> <text>  tap the first element whose text is exactly <text>
#   emu-test.sh back <a|b>            press Back
#   emu-test.sh home <a|b>            press Home
#   emu-test.sh clear <a|b> [n]       delete up to n characters (default 80) from the focused field
#   emu-test.sh text <a|b> <s>        type text into the focused field
#   emu-test.sh shot <a|b>            save a screenshot under local/shots and print its path
# The app sets FLAG_SECURE, so adb screencap comes out black. The accessibility tree still shows the
# text, and the emulator's own screenshot (shot) captures the real screen.
set -u
# Git Bash would otherwise rewrite device paths like /sdcard/... into Windows paths
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="$(cygpath -u "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK folder}")"
ADB="$SDK/platform-tools/adb.exe"
EMU="$SDK/emulator/emulator.exe"
DATA="$HERE/local"
APK="$HERE/../../app/build/outputs/apk/debug/app-debug.apk"

serial() { case "$1" in a) echo emulator-5554 ;; b) echo emulator-5556 ;; *) echo "unknown device $1" >&2; exit 2 ;; esac; }

# Quote for the device's /system/bin/sh so ; ? & and spaces arrive intact
remote_quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# input text drops characters on a busy emulator when given long strings, so send short chunks.
# Spaces become %s (input text's escape for a space) per chunk, so a chunk never splits the escape.
type_chunked() {
  local s=$1 txt=$2 i chunk
  for ((i = 0; i < ${#txt}; i += 10)); do
    chunk=${txt:i:10}
    "$ADB" -s "$s" shell "input text $(remote_quote "${chunk// /%s}")"
    sleep 0.3
  done
}

case "${1:-}" in
  start)
    mkdir -p "$DATA"
    for pair in "obsidian-a 5554" "obsidian-b 5556"; do
      set -- $pair
      nohup "$EMU" -avd "$1" -port "$2" -no-snapshot -no-boot-anim -no-audio -gpu swiftshader_indirect \
        > "$DATA/$1.log" 2>&1 &
    done
    for s in emulator-5554 emulator-5556; do
      "$ADB" -s "$s" wait-for-device
      until [ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
      echo "$s booted"
    done
    ;;
  install)
    for s in emulator-5554 emulator-5556; do "$ADB" -s "$s" install -r "$(cygpath -w "$APK")"; done
    ;;
  launch)
    "$ADB" -s "$(serial "$2")" shell am start -n obsidian.chat/.MainActivity > /dev/null
    ;;
  ui)
    s=$(serial "$2")
    "$ADB" -s "$s" shell uiautomator dump /sdcard/ui.xml > /dev/null
    "$ADB" -s "$s" exec-out cat /sdcard/ui.xml | grep -o '<node [^>]*>' | while IFS= read -r node; do
      t=$(printf '%s' "$node" | sed -n 's/.* text="\([^"]*\)".*/\1/p')
      d=$(printf '%s' "$node" | sed -n 's/.* content-desc="\([^"]*\)".*/\1/p')
      b=$(printf '%s' "$node" | sed -n 's/.* bounds="\([^"]*\)".*/\1/p')
      [ -n "$t$d" ] && printf '%s | %s | %s\n' "$t" "$d" "$b"
    done
    ;;
  tap)
    "$ADB" -s "$(serial "$2")" shell input tap "$3" "$4"
    ;;
  taptext)
    xy=$(bash "$0" ui "$2" | awk -F' [|] ' -v t="$3" '$1 == t { print $3; exit }' \
      | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }')
    [ -n "$xy" ] || { echo "no element with text '$3' on $2" >&2; exit 1; }
    "$ADB" -s "$(serial "$2")" shell input tap $xy
    ;;
  back)
    "$ADB" -s "$(serial "$2")" shell input keyevent KEYCODE_BACK
    ;;
  home)
    "$ADB" -s "$(serial "$2")" shell input keyevent KEYCODE_HOME
    ;;
  clear)
    s=$(serial "$2")
    "$ADB" -s "$s" shell input keyevent KEYCODE_MOVE_END
    "$ADB" -s "$s" shell input keyevent $(for _ in $(seq 1 "${3:-80}"); do printf '67 '; done)
    ;;
  text)
    type_chunked "$(serial "$2")" "$3"
    ;;
  shot)
    out="$DATA/shots/$2"
    mkdir -p "$out"
    "$ADB" -s "$(serial "$2")" emu screenrecord screenshot "$(cygpath -w "$out")" > /dev/null
    ls -t "$out"/*.png | head -1
    ;;
  *)
    sed -n '2,15p' "$0"; exit 2 ;;
esac
