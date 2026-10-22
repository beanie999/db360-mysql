#!/usr/bin/env bash
# Calls random demo app URLs so there is query activity for DB360 to show.
# Usage: ./load-gen.sh [requests] [base-url]   (defaults: 100, http://localhost:8080)
set -u

TOTAL=${1:-100}
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
  /account /account /account
  "/login?user=user7&password=nope" "/login?user=admin&password=admin"
  /pg/slow /pg/lock /pg/dbauth-failure /pg/error
)

for ((n = 1; n <= TOTAL; n++)); do
  p=${PATHS[RANDOM % ${#PATHS[@]}]}
  case "$p" in
    /login*) # give each attempt a source address
      [[ "$p" == *\?* ]] && sep='&' || sep='?'
      p="$p${sep}ip=10.1.$((RANDOM % 250)).$((RANDOM % 250))" ;;
  esac
  status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE$p")
  echo "($n of $TOTAL) status $status $p"
  sleep 0.$((RANDOM % 5))
done
