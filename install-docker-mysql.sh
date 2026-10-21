#!/usr/bin/env bash
# Installs Docker Engine and runs MySQL 8.0 in a container on Ubuntu.
# Usage: MYSQL_ROOT_PASSWORD=... NR_MONITOR_PASSWORD=... ./install-docker-mysql.sh
set -euo pipefail

: "${MYSQL_ROOT_PASSWORD:?set MYSQL_ROOT_PASSWORD}"
: "${NR_MONITOR_PASSWORD:?set NR_MONITOR_PASSWORD}"

# --- Docker Engine (official apt repo) ---
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"

# --- MySQL 8.0 container ---
sudo docker volume create db360_mysql_data >/dev/null
# Safe to re-run: the data volume survives, so this only recreates the container with current settings.
sudo docker rm -f db360-mysql >/dev/null 2>&1 || true
sudo docker run -d --name db360-mysql --restart unless-stopped \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -e MYSQL_DATABASE=db360 \
  -p 3306:3306 \
  -v db360_mysql_data:/var/lib/mysql \
  mysql:8.0 \
  --performance-schema-consumer-events-waits-current=ON \
  --max_digest_length=4096 \
  --performance_schema_max_digest_length=4096 \
  --performance_schema_max_sql_text_length=4096

echo "Waiting for MySQL to accept connections..."
until sudo docker exec db360-mysql mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD" --silent 2>/dev/null; do
  sleep 3
done

# --- Monitoring user for NRDOT / OTel mysql receiver ---
sudo docker exec -i db360-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS 'newrelic'@'%' IDENTIFIED BY '${NR_MONITOR_PASSWORD}' WITH MAX_USER_CONNECTIONS 5;
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'newrelic'@'%';
GRANT SELECT ON performance_schema.* TO 'newrelic'@'%';
-- Query plans: EXPLAIN needs read access to the monitored tables.
GRANT SELECT ON db360.* TO 'newrelic'@'%';
-- Lets the receiver re-enable the wait events consumer if it is ever reset.
GRANT UPDATE ON performance_schema.setup_consumers TO 'newrelic'@'%';
FLUSH PRIVILEGES;
SQL

# Query plans for write statements (explain_mode: procedure in the collector config).
sudo docker exec -i db360-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<'SQL'
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

echo "Done. Log out and back in (or run 'newgrp docker') to use docker without sudo."
sudo docker ps --filter name=db360-mysql
