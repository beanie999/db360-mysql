#!/usr/bin/env bash
# Installs the NRDOT collector (host install) on Ubuntu and points it at New Relic (US).
# Usage: NEW_RELIC_LICENSE_KEY=... NR_MONITOR_PASSWORD=... ./install-nrdot.sh
# Run from the repo directory so nrdot-config.yaml is found.
set -euo pipefail

: "${NEW_RELIC_LICENSE_KEY:?set NEW_RELIC_LICENSE_KEY}"
: "${NR_MONITOR_PASSWORD:?set NR_MONITOR_PASSWORD}"

VERSION=2.7.2
ARCH=$(dpkg --print-architecture)   # amd64 or arm64
DEB="nrdot-collector_${VERSION}_linux_${ARCH}.deb"
URL="https://github.com/newrelic/nrdot-collector-releases/releases/download/${VERSION}/${DEB}"
HERE=$(cd "$(dirname "$0")" && pwd)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
curl -fsSL -o "$WORK/$DEB" "$URL"
curl -fsSL -o "$WORK/$DEB.sum" "$URL.sum"
(cd "$WORK" && sha256sum -c "$DEB.sum" 2>/dev/null || { echo "Checksum check failed"; cat "$DEB.sum"; sha256sum "$DEB"; exit 1; })
sudo dpkg -i "$WORK/$DEB"

sudo install -m 0644 "$HERE/nrdot-config.yaml" /etc/nrdot-collector/config.yaml

# Secrets live in the systemd environment file, readable only by root.
sudo tee -a /etc/nrdot-collector/nrdot-collector.conf >/dev/null <<EOF
NEW_RELIC_LICENSE_KEY=${NEW_RELIC_LICENSE_KEY}
NR_MONITOR_PASSWORD=${NR_MONITOR_PASSWORD}
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.nr-data.net
EOF
sudo chmod 600 /etc/nrdot-collector/nrdot-collector.conf

# Let the collector read /var/log/syslog and auth.log.
sudo usermod -aG adm nrdot-collector

sudo systemctl enable nrdot-collector
sudo systemctl restart nrdot-collector
sleep 5
systemctl --no-pager status nrdot-collector | head -15
