#!/usr/bin/env bash
# register.sh <a|b> <username>
# Fills Obsidian Chat's sign-up form and taps Create account, then waits for the chat list or an
# error. The password comes from local/test-accounts.txt ("<username> <password>" per line).
# Refuses to submit if the username didn't type correctly.
set -u
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
T="$HERE/emu-test.sh"
DATA="$HERE/local"
SDK="$(cygpath -u "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK folder}")"
ADB="$SDK/platform-tools/adb.exe"
dev=$1; user=$2
case "$dev" in a) S=emulator-5554 ;; b) S=emulator-5556 ;; *) echo "unknown device $dev"; exit 2 ;; esac
pw=$(awk -v u="$user" '$1 == u { print $2 }' "$DATA/test-accounts.txt")
[ -n "$pw" ] || { echo "no password for $user in $DATA/test-accounts.txt"; exit 2; }

bash "$T" taptext "$dev" "Username"; sleep 1; bash "$T" text "$dev" "$user"; sleep 1
bash "$T" taptext "$dev" "Password (12+ characters)"; sleep 1; bash "$T" text "$dev" "$pw"; sleep 1
bash "$T" taptext "$dev" "Confirm password"; sleep 1; bash "$T" text "$dev" "$pw"; sleep 2
# Hide the on-screen keyboard if it covers the Create account button (Back only when it is
# showing, otherwise it would leave the app)
"$ADB" -s "$S" shell dumpsys input_method | grep -q 'mInputShown=true' && "$ADB" -s "$S" shell input keyevent 4
sleep 2
screen=$(bash "$T" ui "$dev")
echo "$screen" | grep -q "^$user " || { echo "username not entered; not submitting"; echo "$screen"; exit 1; }

bash "$T" taptext "$dev" "Create account"
for i in $(seq 1 24); do
  sleep 10
  screen=$(bash "$T" ui "$dev")
  echo "$screen" | grep -q '^Chats |' && { echo "$user registered after ~$((i * 10))s"; exit 0; }
  echo "$screen" | grep -q '^Dismiss |' && { echo "error:"; echo "$screen" | head -3; exit 1; }
done
echo "no result after 4 minutes"; echo "$screen"; exit 1
