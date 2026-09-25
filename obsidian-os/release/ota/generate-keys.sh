#!/bin/bash
# Creates the OBSIDIAN release signing keys. Run this ONCE, on a machine you control, and never on
# a rented server. Everything a phone trusts comes from these files: whoever holds them can publish
# an update that every OBSIDIAN phone will install without question.
#
#   generate-keys.sh /path/to/keys
#
# It refuses to run if the directory already holds keys, because regenerating them would orphan
# every phone already running a release signed with the old ones: a phone with a locked bootloader
# will refuse an image signed by a different key, and cannot be recovered without erasing it.
#
# Afterwards:
#   - Back the directory up to at least two places you physically control, encrypted.
#   - Never copy it to the build server, a cloud drive, or anywhere it would be synced.
#   - avb_pkmd.bin is the only file that is meant to be public: it is the fingerprint owners can
#     compare against what their phone shows on its start screen.
set -euo pipefail

DEST=${1:?"give a directory for the keys, for example ./obsidian-keys"}
# Ten thousand days is a little over 27 years. The certificate must outlive every phone it signs,
# because a phone cannot install an update signed by an expired certificate.
DAYS=${DAYS:-10000}
SUBJECT=${SUBJECT:-"/CN=OBSIDIAN/O=OBSIDIAN/C=GB"}

# The keys AOSP expects. Each signs a different class of thing, so a compromise of one does not
# hand over the rest.
KEYS=(
  releasekey    # the operating system image and most of its apps
  platform      # apps that run as the system itself
  shared        # the contacts and telephony family, which OBSIDIAN mostly removes
  media         # media and download providers
  networkstack  # the network stack module
  sdk_sandbox   # the sandbox that untrusted code would run in
  bluetooth     # the bluetooth stack
)

command -v openssl >/dev/null || { echo "openssl is required"; exit 1; }

if [ -e "$DEST" ] && [ -n "$(ls -A "$DEST" 2>/dev/null || true)" ]; then
  echo "$DEST already contains files. Refusing to overwrite existing keys."
  echo "If you genuinely want new keys, move the old directory aside yourself, and understand that"
  echo "phones running the old release cannot take updates signed with the new ones."
  exit 1
fi

mkdir -p "$DEST"
chmod 700 "$DEST"
cd "$DEST"

echo "Generating $(( ${#KEYS[@]} + 1 )) keys. This takes a minute or two."
for name in "${KEYS[@]}"; do
  printf '  %-14s ' "$name"
  # A certificate and its private key. AOSP wants the key as unencrypted PKCS#8 DER, which is what
  # .pk8 is, so the build tools can use it without a passphrase prompt in the middle of a signing run.
  openssl req -new -x509 -newkey rsa:4096 -nodes -sha256 -days "$DAYS" \
    -keyout "$name.tmp.pem" -out "$name.x509.pem" -subj "$SUBJECT" 2>/dev/null
  openssl pkcs8 -topk8 -outform DER -nocrypt -in "$name.tmp.pem" -out "$name.pk8"
  rm -f "$name.tmp.pem"
  chmod 600 "$name.pk8"
  echo "done"
done

# Verified Boot's own key. This is the one the bootloader checks before it will start the phone at
# all, and the one that must be registered when locking the bootloader.
printf '  %-14s ' "avb"
openssl genrsa -out avb.pem 4096 2>/dev/null
chmod 600 avb.pem
echo "done"

cat > README.txt <<'TXT'
OBSIDIAN release signing keys.

Anyone holding these files can publish an operating system update that every OBSIDIAN phone will
accept. Treat them as you would the keys to the company.

  *.pk8           private keys. Never copy these anywhere online.
  *.x509.pem      the matching certificates.
  avb.pem         the Verified Boot key the bootloader checks before starting the phone.
  avb_pkmd.bin    the public half of it, produced by the signing step. This one is meant to be
                  published: it is what a phone owner compares against the fingerprint their phone
                  shows when it starts.

Rules agreed for this project:
  - These never go on the build server or any rented machine.
  - Signing happens on a machine you control, using otatools from the build.
  - Keep at least two encrypted backups in places you physically control.

If these are lost, phones with a locked bootloader cannot be updated, and cannot be recovered
without erasing them. If these are stolen, every phone must be reflashed with new keys, by hand.
TXT

echo
echo "Keys written to $(pwd)"
ls -l | awk 'NR>1 {printf "  %s  %s\n", $5, $9}'
echo
echo "Next: back this directory up, encrypted, in two places you control. Then sign a release with"
echo "sign-release.sh, which also produces avb_pkmd.bin, the public fingerprint to publish."
