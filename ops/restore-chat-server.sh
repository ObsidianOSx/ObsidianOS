#!/bin/bash
# Brings the OBSIDIAN chat server back on a fresh Ubuntu machine, from the 2026-10-03 backup.
#
#     sudo ./restore-chat-server.sh /path/to/chat-server.tar.gz
#
# Run this first, before the website. Every phone already out there depends on this server and on
# nothing else, and it is small: the whole thing is a 14 KB archive.
#
# Two files in that archive are why any of this works, and losing either would have been the end of
# every install:
#   hs_ed25519_secret_key  the onion address compiled into every phone
#   certs/<onion>.crt      the certificate the app pins, by the hash of its public key
# Restoring both means existing phones connect exactly as before, with nothing to change on them.
set -o errexit -o pipefail -o nounset

ARCHIVE=${1:?"usage: restore-chat-server.sh /path/to/chat-server.tar.gz"}
[ -f "$ARCHIVE" ] || { echo "no archive at $ARCHIVE"; exit 1; }
[ "$(id -u)" = 0 ] || { echo "run this with sudo"; exit 1; }

ONION=yvffcuwkw4m3rbhvfrse2hrwnrzt3vhoiwooiiavofgn5dmlhbz7biad.onion
EXPECTED_PIN="bNmFJih+gAfRq73LeDV0CcAiyrbe7ZUgXdSExCKmmIg="

echo "[1/6] installing prosody and tor"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq prosody tor >/dev/null

echo "[2/6] stopping both while we put the files back"
systemctl stop prosody tor 2>/dev/null || true

echo "[3/6] unpacking the backup"
tar -xzf "$ARCHIVE" -C /
# Tor refuses to start if a hidden service directory is readable by anyone else, and prosody will
# not read a config or a key it does not own. Both are silent about it in ways that waste an hour.
chown -R prosody:prosody /etc/prosody /var/lib/prosody
chown -R debian-tor:debian-tor /var/lib/tor/obsidian-xmpp
chmod 700 /var/lib/tor/obsidian-xmpp
chmod 600 /var/lib/tor/obsidian-xmpp/hs_ed25519_secret_key
chmod 640 /etc/prosody/certs/*.key 2>/dev/null || true

# torrc was not in the backup, so it is written here rather than discovered missing later. These
# are the only lines that matter: publish the hidden service, and forward its two ports to prosody
# on loopback. Prosody listens on 127.0.0.1 only, so Tor is the single way in.
echo "[4/6] writing torrc"
if ! grep -q "obsidian-xmpp" /etc/tor/torrc 2>/dev/null; then
  cat >> /etc/tor/torrc <<EOF

# OBSIDIAN chat server. Prosody listens on loopback only, so this is the only route to it.
HiddenServiceDir /var/lib/tor/obsidian-xmpp/
HiddenServicePort 5222 127.0.0.1:5222
HiddenServicePort 5281 127.0.0.1:5281
EOF
  echo "      added the hidden service to /etc/tor/torrc"
else
  echo "      /etc/tor/torrc already mentions it, left alone"
fi

echo "[5/6] starting tor, then prosody"
systemctl start tor
for i in $(seq 1 30); do [ -s /var/lib/tor/obsidian-xmpp/hostname ] && break; sleep 2; done
systemctl start prosody

echo "[6/6] checking it came back as the same server"
GOT_ONION=$(cat /var/lib/tor/obsidian-xmpp/hostname 2>/dev/null || echo "none")
GOT_PIN=$(openssl x509 -in "/etc/prosody/certs/$ONION.crt" -pubkey -noout 2>/dev/null \
  | openssl pkey -pubin -outform DER 2>/dev/null \
  | openssl dgst -sha256 -binary | openssl base64)

echo "      onion:    $GOT_ONION"
echo "      expected: $ONION"
echo "      pin:      $GOT_PIN"
echo "      expected: $EXPECTED_PIN"

fail=0
[ "$GOT_ONION" = "$ONION" ] || { echo "  THE ONION ADDRESS IS WRONG. Phones cannot reach this server."; fail=1; }
[ "$GOT_PIN" = "$EXPECTED_PIN" ] || { echo "  THE CERTIFICATE IS WRONG. Phones will refuse to connect."; fail=1; }
systemctl is-active --quiet prosody || { echo "  prosody is not running"; fail=1; }
systemctl is-active --quiet tor || { echo "  tor is not running"; fail=1; }

if [ "$fail" = 0 ]; then
  echo
  echo "  Restored. Existing phones reach this with nothing changed on them."
  echo "  Accounts that came back:"
  ls -1 "/var/lib/prosody/${ONION//./%2e}/accounts/" 2>/dev/null | sed 's/\.dat$//;s/^/    /' || echo "    none"
else
  echo
  echo "  Something is wrong above. Do not announce the server as back until it is fixed."
  exit 1
fi
