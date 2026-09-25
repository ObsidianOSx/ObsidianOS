# Updating a phone that is already in someone's hands

Until this existed, changing anything on an OBSIDIAN phone meant erasing it and installing again.
Nobody does that monthly, so phones would quietly fall behind on Android's security fixes and
become exactly the thing this project is supposed to avoid.

These phones carry two copies of the operating system. An update is written into the copy that is
not running while the phone is still being used, and the next restart starts the new one. If the
new copy fails to start, the phone falls back to the old one by itself, so a bad update cannot
leave someone holding a phone that will not turn on.

## What a phone checks before it replaces its own operating system

Three things, none of which involve trusting the website, the network, or Tor.

1. **The update information is signed.** `updates/<device>.json` is signed with the release key, and
   the phone checks that signature against a certificate built into it. A web server that has been
   taken over can refuse to answer, but it cannot invent a version, point phones at a file of its
   choosing, or send everyone back to an old release with a known hole in it.
2. **The package matches what that signed information says**, by size and by SHA-256, checked as it
   downloads rather than afterwards.
3. **The package carries its own signature**, which `update_engine` checks against a key built into
   the phone before it writes a single byte. This is the check that would catch everything else
   having been fooled.

A phone also refuses anything older than what it already runs, so a genuine but old update cannot
be replayed at it.

## The keys

The release keys are what a phone trusts. Whoever holds them can publish an update that every
OBSIDIAN phone installs without question, so they are generated on a machine we control and never
exist anywhere else. That is why signing does not happen on the build server: the server builds,
and signing happens where the keys are.

```bash
./generate-keys.sh ~/obsidian-keys     # once, ever. Then back it up, encrypted, twice.
```

Losing them means phones with a locked bootloader cannot be updated and cannot be recovered without
erasing them. Having them stolen means every phone must be reflashed by hand with new keys. Back
them up properly, offline, in two places you physically control.

`avb_pkmd.bin`, which appears when you first sign a release, is the only one of these meant to be
public. It is the fingerprint an owner compares against what their phone shows when it starts.

## Making a release

```bash
# 1. On the build server, build as usual, with the version decided up front so the image can
#    stamp it into itself:
OBSIDIAN_VERSION=2026.10.01 ./build-pixel.sh shiba userdebug

# 2. Here, where the keys are. Brings down the build and the signing tools that match it:
SERVER=ubuntu@<build-server> SSH_KEY=~/key.pem ./fetch-for-signing.sh shiba

# 3. Sign it, and build both the flashable image and the update packages. PREVIOUS is the signed
#    target-files from the last release: without it every phone downloads the whole system again
#    instead of only what changed.
KEYS=~/obsidian-keys OTATOOLS=./to-sign/shiba/otatools.zip \
  PREVIOUS=./signed/shiba-2026.09.25/shiba-2026.09.25-signed-target-files.zip \
  ./sign-release.sh shiba 2026.10.01 ./to-sign/shiba/shiba-target_files.zip

# 4. Publish the update packages, so phones already running OBSIDIAN find them:
SITE=ubuntu@<web-server> SSH_KEY=~/key.pem KEYS=~/obsidian-keys \
  ./publish-ota.sh shiba 2026.10.01 ./signed/shiba-2026.10.01
```

**Keep every signed target-files.** The next release needs the previous one to build a small update.
Without it, a monthly security fix costs each phone a 2 GB download over Tor instead of a few tens
of megabytes.

## Turning updates on in the app

The app only accepts updates it can check, so a build with no release certificate says so on the
security screen and offers nothing. Point it at the certificate in `local.properties`:

```properties
obsidian.updateCert=../obsidian-keys/releasekey.x509.pem
obsidian.updateDomain=obsidianos.org
```

Test builds leave both unset. They then say, honestly, that they cannot receive updates.

## Locking the bootloader

Only worth doing once releases are signed with real keys, and it is the step that makes the rest
mean something: until then the phone will start anything, so a signed update is a convenience
rather than a protection.

1. Install a release signed with the release keys.
2. Register the Verified Boot key: `fastboot flash avb_custom_key avb_pkmd.bin`
3. `fastboot flashing lock`

After that the phone refuses to start anything not signed by those keys, and shows the fingerprint
of the key it did start, which an owner can compare against the published `avb_pkmd.bin`.

Do not lock a phone running a test build. A phone locked to an operating system it cannot verify
refuses to start, and getting out of that means erasing it.
