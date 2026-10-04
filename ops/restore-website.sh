#!/bin/bash
# Brings obsidianos.org back on a fresh Ubuntu machine, from the 2026-10-03 backup.
#
#     sudo ./restore-website.sh /path/to/website-files.tar.gz /path/to/server-config.tar.gz
#
# Run the chat server first: the phones depend on that and not on this. This part is mostly empty
# until the release zips are copied up separately, which is the slow bit at roughly 24 GB.
#
# The certificate in the backup belongs to the domain, not the machine, so it keeps working once
# DNS points here. Until the A record for obsidianos.org is moved to this server's address, HTTPS
# will not answer and certbot cannot renew.
set -o errexit -o pipefail -o nounset

SITE=${1:?"usage: restore-website.sh website-files.tar.gz server-config.tar.gz"}
CONF=${2:?"usage: restore-website.sh website-files.tar.gz server-config.tar.gz"}
for f in "$SITE" "$CONF"; do [ -f "$f" ] || { echo "no archive at $f"; exit 1; }; done
[ "$(id -u)" = 0 ] || { echo "run this with sudo"; exit 1; }

echo "[1/5] installing nginx and certbot"
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx certbot >/dev/null

echo "[2/5] unpacking the site and its configuration"
mkdir -p /var/www
tar -xzf "$SITE" -C /var/www
tar -xzf "$CONF" -C /
chown -R root:root /var/www/obsidian
find /var/www/obsidian -type f -exec chmod 644 {} +
find /var/www/obsidian -type d -exec chmod 755 {} +

echo "[3/5] enabling the site"
# The config came off a machine running nginx 1.28, where http2 is a directive of its own. Ubuntu
# 24.04 ships 1.24, which predates that and wants the flag on the listen line instead. Same result,
# older spelling. Done by hand the first two times this was restored, so it lives here now.
NGINX_VER=$(nginx -v 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
if [ -n "$NGINX_VER" ] && grep -q '^\s*http2 on;' /etc/nginx/sites-available/obsidian 2>/dev/null; then
  # The standalone http2 directive arrived in 1.25.1. Anything older needs it on the listen line.
  oldest=$(printf '%s\n1.25.1\n' "$NGINX_VER" | sort -V | head -1)
  if [ "$oldest" = "$NGINX_VER" ] && [ "$NGINX_VER" != "1.25.1" ]; then
    echo "      nginx $NGINX_VER predates the http2 directive, moving it onto the listen line"
    sed -i 's/^\(\s*listen 443 ssl\)\(.*\);/\1\2 http2;/' /etc/nginx/sites-available/obsidian
    sed -i '/^\s*http2 on;\s*$/d' /etc/nginx/sites-available/obsidian
  fi
fi
# The release index carries the checksums and the per phone indexes the browser installer reads.
# Without it the site serves builds that nothing can verify and the installer cannot find them.
if [ -n "${INDEX:-}" ] && [ -f "$INDEX" ]; then
  tar -xzf "$INDEX" -C /var/www/obsidian
  echo "      release index restored"
fi
ln -sf /etc/nginx/sites-available/obsidian /etc/nginx/sites-enabled/obsidian
rm -f /etc/nginx/sites-enabled/default
nginx -t

echo "[4/5] starting nginx"
systemctl enable --now nginx >/dev/null 2>&1 || true
systemctl reload nginx

echo "[5/5] checking what came back"
# The footer on every page promises nothing counts your visit, so logging stays off. Check that the
# restored config did not quietly bring it back with a distribution default.
if [ -s /var/log/nginx/access.log ]; then
  echo "  WARNING: /var/log/nginx/access.log has content. Visitor logging must stay off:"
  echo "           check access_log off; is present in nginx.conf and in the site."
fi
echo "  pages present: $(find /var/www/obsidian -maxdepth 1 -name '*.html' | wc -l)"
echo "  releases present: $(find /var/www/obsidian/releases -name '*.zip' 2>/dev/null | wc -l) of 12"
echo "  local answer: $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/ || echo failed)"
echo
echo "  Still to do by hand:"
echo "    1. Point the A record for obsidianos.org at this machine, and the www CNAME at the apex."
echo "    2. Copy the 12 release zips into /var/www/obsidian/releases, then verify every one:"
echo "         cd /var/www/obsidian/releases && sha256sum -c *.sha256"
echo "    3. Once DNS has moved, renew the certificate:"
echo "         certbot certonly --webroot -w /var/www/obsidian -d obsidianos.org -d www.obsidianos.org"
