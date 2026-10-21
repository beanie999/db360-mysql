#!/usr/bin/env bash
# Installs PostgreSQL on the host (no Docker), configures it for monitoring, and loads the demo data.
# Usage: PG_DEMO_PASSWORD=... PG_MONITOR_PASSWORD=... ./install-postgres.sh
# Safe to re-run: it resets the demo tables and passwords.
set -euo pipefail

: "${PG_DEMO_PASSWORD:?set PG_DEMO_PASSWORD}"
: "${PG_MONITOR_PASSWORD:?set PG_MONITOR_PASSWORD}"

HERE=$(cd "$(dirname "$0")" && pwd)

sudo apt-get update
sudo apt-get install -y postgresql postgresql-contrib

PGVER=$(ls /etc/postgresql | sort -V | tail -1)
echo "Configuring PostgreSQL $PGVER..."

# pg_stat_statements needs a restart; the log settings make logins and slow queries visible in the log file.
sudo tee /etc/postgresql/"$PGVER"/main/conf.d/db360.conf >/dev/null <<CONF
shared_preload_libraries = 'pg_stat_statements'
compute_query_id = on
track_io_timing = on
track_activity_query_size = 4096
pg_stat_statements.max = 10000
log_connections = on
log_disconnections = on
log_lock_waits = on
log_min_duration_statement = 500
log_line_prefix = '%m [%p] %q%u@%d '
CONF
sudo systemctl restart postgresql

psql_admin() { sudo -u postgres psql -v ON_ERROR_STOP=1 "$@"; }

# Roles and database. Passwords go in as psql variables so quoting is handled safely.
psql_admin -v demo_pw="$PG_DEMO_PASSWORD" -v mon_pw="$PG_MONITOR_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE demo LOGIN PASSWORD %L', :'demo_pw')
  WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'demo') \gexec
SELECT format('ALTER ROLE demo PASSWORD %L', :'demo_pw') \gexec
SELECT format('CREATE ROLE newrelic LOGIN PASSWORD %L', :'mon_pw')
  WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'newrelic') \gexec
SELECT format('ALTER ROLE newrelic PASSWORD %L', :'mon_pw') \gexec
GRANT pg_monitor TO newrelic;
SELECT 'CREATE DATABASE db360 OWNER demo'
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'db360') \gexec
SQL

# Files are fed through stdin because the postgres user cannot read files in a home directory.
echo "Creating schema..."
psql_admin -d db360 < "$HERE/demo-data/pg-schema.sql"
echo "Seeding data (this can take a few seconds)..."
psql_admin -d db360 < "$HERE/demo-data/pg-seed.sql"

echo "Checking the demo login works over TCP..."
PGPASSWORD="$PG_DEMO_PASSWORD" psql -h 127.0.0.1 -U demo -d db360 -tAc "SELECT 'demo user ok'"
PGPASSWORD="$PG_MONITOR_PASSWORD" psql -h 127.0.0.1 -U newrelic -d db360 -tAc "SELECT 'monitor user ok, pg_stat_statements rows: ' || count(*) FROM pg_stat_statements"
