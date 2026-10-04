#!/bin/bash
# Keeps obsidianos.org pointing at this house, because the address changes without warning.
#
# The site is served from a home connection now, and the public address on one of those is not
# fixed: a router reboot or the ISP's own housekeeping can change it, and when it does the domain
# points at a stranger's connection until someone notices. This runs every few minutes, and does
# nothing at all unless the address has actually moved.
#
# Install:
#   sudo cp ddns-update.sh /usr/local/bin/
#   sudo install -m 600 /dev/null /etc/obsidian-ddns.conf   then put the credentials in it
#   sudo systemctl enable --now obsidian-ddns.timer
#
# /etc/obsidian-ddns.conf holds two lines, and nothing else should ever be able to read it:
#   GODADDY_KEY=...
#   GODADDY_SECRET=...
# Make them at developer.godaddy.com, on a Production key. They can change any DNS record on the
# account, so the file is 0600 and owned by root.
set -o errexit -o pipefail -o nounset

CONF=${CONF:-/etc/obsidian-ddns.conf}
DOMAIN=${DOMAIN:-obsidianos.org}
RECORD=${RECORD:-@}
STATE=${STATE:-/var/lib/obsidian-ddns.last}
LOG=${LOG:-/var/log/obsidian-ddns.log}

say() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$LOG"; }

[ -r "$CONF" ] || { say "no readable config at $CONF"; exit 1; }
# shellcheck disable=SC1090
. "$CONF"
: "${GODADDY_KEY:?GODADDY_KEY missing from $CONF}"
: "${GODADDY_SECRET:?GODADDY_SECRET missing from $CONF}"

# Ask more than one place what our address is. A single service having a bad day should not be able
# to point the domain somewhere wrong, so two have to agree before anything is changed.
ip1=$(curl -s --max-time 15 https://api.ipify.org 2>/dev/null || true)
ip2=$(curl -s --max-time 15 https://ifconfig.me/ip 2>/dev/null || true)
valid() { [[ $1 =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; }

if ! valid "${ip1:-}"; then say "could not determine the address"; exit 1; fi
if valid "${ip2:-}" && [ "$ip1" != "$ip2" ]; then
  say "the two lookups disagree ($ip1 and $ip2), leaving DNS alone"
  exit 1
fi

# Private and carrier grade addresses mean we are behind something that makes this pointless, and
# publishing one would break the site rather than fix it.
case "$ip1" in
  10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*|127.*|169.254.*)
    say "$ip1 is a private address, not publishing it"; exit 1 ;;
  100.6[4-9].*|100.[7-9][0-9].*|100.1[0-1][0-9].*|100.12[0-7].*)
    say "$ip1 is carrier grade NAT, the site cannot be reached this way"; exit 1 ;;
esac

last=$(cat "$STATE" 2>/dev/null || echo "")
if [ "$ip1" = "$last" ]; then exit 0; fi   # nothing to do, and the common case

current=$(curl -s --max-time 20 \
  -H "Authorization: sso-key ${GODADDY_KEY}:${GODADDY_SECRET}" \
  "https://api.godaddy.com/v1/domains/${DOMAIN}/records/A/${RECORD}" 2>/dev/null \
  | grep -oE '"data":"[^"]+"' | head -1 | cut -d'"' -f4 || true)

if [ "$current" = "$ip1" ]; then
  printf '%s' "$ip1" > "$STATE"
  exit 0
fi

code=$(curl -s -o /tmp/ddns-reply.$$ -w '%{http_code}' --max-time 25 -X PUT \
  -H "Authorization: sso-key ${GODADDY_KEY}:${GODADDY_SECRET}" \
  -H "Content-Type: application/json" \
  -d "[{\"data\":\"${ip1}\",\"ttl\":600}]" \
  "https://api.godaddy.com/v1/domains/${DOMAIN}/records/A/${RECORD}" 2>/dev/null || echo 000)

if [ "$code" = "200" ] || [ "$code" = "204" ]; then
  printf '%s' "$ip1" > "$STATE"
  say "address changed from ${current:-unknown} to $ip1, DNS updated"
else
  say "DNS update refused, HTTP $code: $(head -c 200 /tmp/ddns-reply.$$ 2>/dev/null)"
  rm -f /tmp/ddns-reply.$$
  exit 1
fi
rm -f /tmp/ddns-reply.$$
