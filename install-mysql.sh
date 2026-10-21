#!/usr/bin/env bash
# Installs MySQL on the host, configures it for monitoring, and loads the demo data.
# Usage: NR_MONITOR_PASSWORD=... DEMO_DB_PASSWORD=... ./install-mysql.sh
# Safe to re-run: it resets the demo tables and the two users' passwords.
set -euo pipefail

: "${NR_MONITOR_PASSWORD:?set NR_MONITOR_PASSWORD (password for the newrelic monitoring user)}"
: "${DEMO_DB_PASSWORD:?set DEMO_DB_PASSWORD (password for the demo app user)}"

HERE=$(cd "$(dirname "$0")" && pwd)

sudo apt-get update
sudo apt-get install -y mysql-server

# Settings that DB360 needs: wait events, and long enough query text for query plans.
sudo tee /etc/mysql/mysql.conf.d/db360.cnf >/dev/null <<'CONF'
[mysqld]
performance-schema-consumer-events-waits-current = ON
max_digest_length = 4096
performance_schema_max_digest_length = 4096
performance_schema_max_sql_text_length = 4096
CONF
sudo systemctl restart mysql

echo "Waiting for MySQL to accept connections..."
until sudo mysqladmin ping --silent 2>/dev/null; do
  sleep 2
done

# On Ubuntu, root connects through the local socket, so `sudo mysql` needs no password.
mysql_root() { sudo mysql "$@"; }

mysql_root <<SQL
CREATE DATABASE IF NOT EXISTS db360;

-- Monitoring user for the NRDOT mysql receiver.
CREATE USER IF NOT EXISTS 'newrelic'@'%' IDENTIFIED BY '${NR_MONITOR_PASSWORD}' WITH MAX_USER_CONNECTIONS 5;
ALTER USER 'newrelic'@'%' IDENTIFIED BY '${NR_MONITOR_PASSWORD}';
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'newrelic'@'%';
GRANT SELECT ON performance_schema.* TO 'newrelic'@'%';
-- Query plans: EXPLAIN needs read access to the monitored tables.
GRANT SELECT ON db360.* TO 'newrelic'@'%';
-- Lets the receiver re-enable the wait events consumer if it is ever reset.
GRANT UPDATE ON performance_schema.setup_consumers TO 'newrelic'@'%';

-- Demo app user.
CREATE USER IF NOT EXISTS 'demo'@'%' IDENTIFIED BY '${DEMO_DB_PASSWORD}';
ALTER USER 'demo'@'%' IDENTIFIED BY '${DEMO_DB_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON db360.* TO 'demo'@'%';
FLUSH PRIVILEGES;
SQL

# Query plans for write statements (explain_mode: procedure in the collector config).
mysql_root <<'SQL'
DROP PROCEDURE IF EXISTS db360.explain_statement;
DELIMITER $$
CREATE DEFINER = CURRENT_USER PROCEDURE db360.explain_statement(IN stmt LONGTEXT)
SQL SECURITY DEFINER
BEGIN
  SET @nrmysql_stmt = CONCAT('EXPLAIN FORMAT=json ', stmt);
  PREPARE nrmysql_explain_stmt FROM @nrmysql_stmt;
  EXECUTE nrmysql_explain_stmt;
  DEALLOCATE PREPARE nrmysql_explain_stmt;
END $$
DELIMITER ;
GRANT EXECUTE ON PROCEDURE db360.explain_statement TO 'newrelic'@'%';
SQL

echo "Creating schema..."
mysql_root < "$HERE/demo-data/schema.sql"
echo "Seeding data (this can take a minute)..."
mysql_root < "$HERE/demo-data/seed.sql"

echo "Checking the new users can log in over TCP..."
MYSQL_PWD="$DEMO_DB_PASSWORD" mysql -h 127.0.0.1 -u demo db360 -N -e "SELECT 'demo user ok, orders: ', COUNT(*) FROM orders"
MYSQL_PWD="$NR_MONITOR_PASSWORD" mysql -h 127.0.0.1 -u newrelic -N -e "SELECT 'monitor user ok, wait events consumer enabled: ', ENABLED FROM performance_schema.setup_consumers WHERE NAME = 'events_waits_current'"
