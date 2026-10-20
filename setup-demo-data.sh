#!/usr/bin/env bash
# Loads the demo schema and data into the db360-mysql container and creates the app user.
# Usage: MYSQL_ROOT_PASSWORD=... DEMO_DB_PASSWORD=... ./setup-demo-data.sh
# Re-running drops and recreates the demo tables.
set -euo pipefail

: "${MYSQL_ROOT_PASSWORD:?set MYSQL_ROOT_PASSWORD}"
: "${DEMO_DB_PASSWORD:?set DEMO_DB_PASSWORD}"

HERE=$(cd "$(dirname "$0")" && pwd)
mysql_root() { sudo docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" db360-mysql mysql -uroot "$@"; }

echo "Creating schema..."
mysql_root < "$HERE/demo-data/schema.sql"
echo "Seeding data (this can take a minute)..."
mysql_root < "$HERE/demo-data/seed.sql"

mysql_root <<SQL
CREATE USER IF NOT EXISTS 'demo'@'%' IDENTIFIED BY '${DEMO_DB_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON db360.* TO 'demo'@'%';
SQL

mysql_root -e "SELECT 'customers' AS tbl, COUNT(*) AS n FROM db360.customers
  UNION ALL SELECT 'products', COUNT(*) FROM db360.products
  UNION ALL SELECT 'orders', COUNT(*) FROM db360.orders
  UNION ALL SELECT 'order_items', COUNT(*) FROM db360.order_items;"
