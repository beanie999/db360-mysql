#!/usr/bin/env bash
# Downloads the New Relic Java agent into ~/newrelic-agent (override with AGENT_DIR).
# Usage: ./install-nr-agent.sh
set -euo pipefail

AGENT_DIR=${AGENT_DIR:-$HOME/newrelic-agent}
URL=https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic-java.zip

command -v unzip >/dev/null || sudo apt-get install -y unzip

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
curl -fsSL -o "$WORK/newrelic-java.zip" "$URL"
mkdir -p "$AGENT_DIR"
unzip -q -o "$WORK/newrelic-java.zip" -d "$WORK"
cp -r "$WORK"/newrelic/. "$AGENT_DIR"/

echo "Installed to $AGENT_DIR"
ls "$AGENT_DIR"/newrelic.jar
