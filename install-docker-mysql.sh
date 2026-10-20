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
sudo docker run -d --name db360-mysql --restart unless-stopped \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
  -e MYSQL_DATABASE=db360 \
  -p 3306:3306 \
  -v db360_mysql_data:/var/lib/mysql \
  mysql:8.0

echo "Waiting for MySQL to accept connections..."
until sudo docker exec db360-mysql mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD" --silent 2>/dev/null; do
  sleep 3
done

# --- Monitoring user for NRDOT / OTel mysql receiver ---
sudo docker exec -i db360-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS 'newrelic'@'%' IDENTIFIED BY '${NR_MONITOR_PASSWORD}' WITH MAX_USER_CONNECTIONS 5;
GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'newrelic'@'%';
GRANT SELECT ON performance_schema.* TO 'newrelic'@'%';
FLUSH PRIVILEGES;
SQL

echo "Done. Log out and back in (or run 'newgrp docker') to use docker without sudo."
sudo docker ps --filter name=db360-mysql
