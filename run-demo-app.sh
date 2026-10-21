#!/usr/bin/env bash
# Runs the demo app with the New Relic Java agent attached (US region by default).
# Usage: DB_PASSWORD=... NEW_RELIC_LICENSE_KEY=... ./run-demo-app.sh
# Run ./install-nr-agent.sh first and build the app with: (cd demo-app && mvn -q package)
set -euo pipefail

: "${DB_PASSWORD:?set DB_PASSWORD}"
: "${NEW_RELIC_LICENSE_KEY:?set NEW_RELIC_LICENSE_KEY}"

HERE=$(cd "$(dirname "$0")" && pwd)
AGENT_DIR=${AGENT_DIR:-$HOME/newrelic-agent}

# The agent reads these environment variables, so newrelic.yml needs no edits.
export NEW_RELIC_APP_NAME=${NEW_RELIC_APP_NAME:-db360-demo-app}
export NEW_RELIC_DISTRIBUTED_TRACING_ENABLED=true
# Forward the app's log output to New Relic, linked to the traces.
export NEW_RELIC_APPLICATION_LOGGING_ENABLED=true
export NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED=true
export NEW_RELIC_APPLICATION_LOGGING_LOCAL_DECORATING_ENABLED=true

exec java -javaagent:"$AGENT_DIR"/newrelic.jar -jar "$HERE"/demo-app/target/db360-demo.jar
