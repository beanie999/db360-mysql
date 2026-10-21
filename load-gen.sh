#!/usr/bin/env bash
# Calls random demo app URLs so there is query activity for DB360 to show.
# Usage: ./load-gen.sh [seconds] [base-url]   (defaults: 300, http://localhost:8080)
set -u

DURATION=${1:-300}
BASE=${2:-http://localhost:8080}
PATHS=(
  /customer /customer /customer /customer
  /orders/recent /orders/recent
  /search /search
  /report/sales
  /report/status
  /order /order
  /slow
  /lock
  /error
  /login /login /login /login /login /login
  /login/history /login/report
  "/login?user=user7&password=nope" "/login?user=admin&password=admin"
  /pg/slow /pg/lock /pg/dbauth-failure /pg/error
)

end=$((SECONDS + DURATION))
while [ "$SECONDS" -lt "$end" ]; do
  p=${PATHS[RANDOM % ${#PATHS[@]}]}
  case "$p" in
    /login*) # give each attempt a source address
      [[ "$p" == *\?* ]] && sep='&' || sep='?'
      p="$p${sep}ip=10.1.$((RANDOM % 250)).$((RANDOM % 250))" ;;
  esac
  curl -s -o /dev/null -w "%{http_code} $p\n" "$BASE$p"
  sleep 0.$((RANDOM % 5))
done
