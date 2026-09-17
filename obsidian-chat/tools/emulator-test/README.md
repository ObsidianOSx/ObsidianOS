# Two-phone emulator test

Checks the PGP + OMEMO system end to end on two Android emulators, with no real phone needed:

- sign-up over Tor with a username, a password and a PGP key made on the phone
- adding a contact by username, and accepting the request
- each phone fetches the other's PGP identity and checks the signatures binding their OMEMO device to it
- the fingerprint each phone shows for the other matches the other phone's own
- a sealed message each way: it arrives encrypted, opens on a tap, and deleting it removes the sender's copy too

The app blocks screenshots (`FLAG_SECURE`), so these scripts read the screen through Android's accessibility tree and type through `adb`.

## One-time setup

Needs JDK 21, the Android SDK and Git Bash. The emulators need about 2.5 GB of RAM each, so close other heavy programs on a 16 GB PC.

```bash
export JAVA_HOME='<path-to-JDK-21>'
export ANDROID_HOME='<path-to-Android-SDK>'
AVD="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager.bat"
"$AVD" create avd -n obsidian-a -k "system-images;android-36;default;x86_64" -d pixel_8
"$AVD" create avd -n obsidian-b -k "system-images;android-36;default;x86_64" -d pixel_8
# Let the PC keyboard type into the emulators
sed -i 's/^hw.keyboard=no$/hw.keyboard=yes/' ~/.android/avd/obsidian-{a,b}.avd/config.ini
```

## Using the phones by hand

Open **Obsidian Chat** from the home screen, wait for "Tor connected" at the top (the first start after a reset downloads the Tor directory and takes longer), then pick a username and password. If **Create account** stays grey, the line under it says what's missing.

- **Add contact** takes just the other person's username; the app says so if nobody has that name.
- **Security** on the chat list shows live Tor, server, OMEMO and storage status.
- **My key → Erase this account** wipes the phone (and deletes the account on the server when online) so you can register someone new.

## Automated run

Put each test username and a password of 12 or more characters in `local/test-accounts.txt`, one `username password` pair per line. `local/` is git-ignored.

From `obsidian-chat/`:

```bash
./gradlew.bat testDebugUnitTest assembleDebug
bash tools/emulator-test/emu-test.sh start
bash tools/emulator-test/emu-test.sh install
bash tools/emulator-test/emu-test.sh launch a
bash tools/emulator-test/emu-test.sh launch b
bash tools/emulator-test/register.sh a alice
bash tools/emulator-test/register.sh b bob
bash tools/emulator-test/e2e.sh alice bob
```

`e2e.sh` ends with `PASS` or with `FAIL:` and the step that failed. It is safe to rerun on the same accounts: it skips pairing and verification that already happened, and sends fresh messages each time.

A delete can take a few minutes to reach the other phone if a Tor circuit stalls. The app pings every minute and resumes the session when a ping goes unanswered, so nothing is lost. The script allows 6 minutes for this.
