#!/usr/bin/env bash
# e2e.sh <username on a> <username on b>
# Two-phone check of the PGP + OMEMO system, on accounts that are registered (see register.sh):
#   contact request and accept, key exchange, fingerprint comparison, verification, then in each
#   direction a sealed message, tap to open, delete, and the delete reaching the sender's phone.
# Safe to rerun: skips pairing and verification that already happened.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
T="$HERE/emu-test.sh"
A_NAME=$1; B_NAME=$2

say() { echo "[$(date +%H:%M:%S)] $*"; }
fail() { say "FAIL: $*"; exit 1; }
ui() { bash "$T" ui "$1"; }
tap() { bash "$T" taptext "$1" "$2"; }

# wait_for <dev> <regex> <seconds>: until a line of on-screen text matches
wait_for() {
  local i
  for ((i = 0; i < $3; i += 5)); do ui "$1" | grep -q -E "$2" && return 0; sleep 5; done
  return 1
}
wait_for_absent() {
  local i
  for ((i = 0; i < $3; i += 5)); do ui "$1" | grep -q -E "$2" || return 0; sleep 5; done
  return 1
}
# Back to the chat list (Back first hides the keyboard if it is showing)
home() {
  local _
  for _ in 1 2 3 4; do ui "$1" | grep -q '^Chats |' && return 0; bash "$T" back "$1"; sleep 2; done
  fail "$1 did not return to the chat list"
}
fingerprint_on_screen() { ui "$1" | grep -o -E '^([0-9A-F]{4} ){9}[0-9A-F]{4}' | head -1; }

say "waiting for both phones to be online"
wait_for a '· online' 180 || fail "a is not online"
wait_for b '· online' 180 || fail "b is not online"
home a; home b

if ! ui b | grep -q "^$A_NAME |"; then
  say "$A_NAME adds $B_NAME"
  tap a "Add contact"; sleep 3
  tap a "Their username"; sleep 1; bash "$T" text a "$B_NAME"; sleep 2
  tap a "Add"; sleep 3
  wait_for b "Wants to add you" 180 || fail "the contact request never reached $B_NAME"
  say "$B_NAME accepts"
  tap b "Accept"
fi

say "waiting for each phone to receive and check the other's keys"
wait_for a "Keys received|Verified in person" 300 || fail "$A_NAME never received $B_NAME's keys"
wait_for b "Keys received|Verified in person" 300 || fail "$B_NAME never received $A_NAME's keys"

own_fingerprint() { tap "$1" "My key"; sleep 3; fingerprint_on_screen "$1"; home "$1"; }
FP_A=$(own_fingerprint a); FP_B=$(own_fingerprint b)
[ -n "$FP_A" ] && [ -n "$FP_B" ] || fail "could not read the phones' own fingerprints"
say "$A_NAME's key: $FP_A"
say "$B_NAME's key: $FP_B"

# verify <dev> <peer name> <fingerprint the peer's own phone shows>; leaves <dev> in the chat
verify() {
  tap "$1" "$2"; sleep 3
  tap "$1" "Verify" 2> /dev/null || tap "$1" "Verified"; sleep 3
  local seen; seen=$(fingerprint_on_screen "$1")
  [ "$seen" = "$3" ] || fail "$1 shows $2's key as '$seen', but $2's phone shows '$3'"
  ui "$1" | grep -q "^It matches what they showed me |" && { tap "$1" "It matches what they showed me"; sleep 2; }
  ui "$1" | grep -q "You verified this fingerprint in person" || fail "$1 did not record the verification"
  bash "$T" back "$1"; sleep 2
}
verify a "$B_NAME" "$FP_B"; say "$A_NAME's phone shows $B_NAME's real key; verified"
verify b "$A_NAME" "$FP_A"; say "$B_NAME's phone shows $A_NAME's real key; verified"

# message <from dev> <to dev> <text>: both phones are in their chat with each other
message() {
  local from=$1 to=$2 text=$3
  tap "$from" "Encrypted message"; sleep 1; bash "$T" text "$from" "$text"; sleep 2
  tap "$from" "Send"; sleep 3
  wait_for "$from" "^$text |" 30 || fail "$from did not show the sent message"
  wait_for "$to" "^Sealed message |" 180 || fail "$to never received a sealed message"
  ui "$to" | grep -q "^$text |" && fail "$to showed the message before it was opened"
  tap "$to" "Sealed message"; sleep 3
  wait_for "$to" "^$text |" 15 || fail "$to opened a message that doesn't match what was sent"
  ui "$to" | grep -q "Unverified device" && fail "$to marked the sender's device as unverified"
  say "$to received the sealed message and opened it"
  # Opening grows the bubble and scrolls the list, so let it settle and retry a tap that missed
  local _
  for _ in 1 2 3; do
    sleep 3
    tap "$to" "Delete" 2> /dev/null
    wait_for_absent "$to" "^$text |" 15 && break
  done
  ui "$to" | grep -q "^$text |" && fail "$to could not delete the message"
  wait_for_absent "$from" "^$text |" 360 || fail "the delete never reached $from"
  say "$to deleted it and $from's copy was removed too"
}
stamp=$(date +%H%M%S)
message a b "Hello $B_NAME, check $stamp"
message b a "Hi $A_NAME, reply $stamp"

say "PASS: contact, key exchange, fingerprints, verification, sealed messages and deletes all work both ways"
