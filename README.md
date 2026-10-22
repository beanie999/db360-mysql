# New Relic DB360 demo

A self-contained demo of New Relic database monitoring (DB360) on a single Ubuntu VM.

The VM runs:

- **MySQL** and **PostgreSQL**, installed directly on the host, each with demo data.
- A small **Java web app** that runs example queries against both databases when a URL is called. It includes login success/failure scenarios, slow queries, row-lock contention and errors.
- The **New Relic Java agent**, attached to the app (APM, distributed tracing, logs in context, errors).
- The **NRDOT collector** (New Relic Distribution of OpenTelemetry), which monitors the host and both databases and sends everything to New Relic.

```
                      Ubuntu VM
  ┌───────────────────────────────────────────────────┐
  │  load-gen.sh ──HTTP──▶ Java demo app :8080         │
  │                         │ (+ New Relic Java agent) │
  │                         ├──JDBC──▶ MySQL :3306      │
  │                         └──JDBC──▶ PostgreSQL :5432 │
  │                                                     │
  │  NRDOT collector ──▶ host metrics, syslog/auth log, │
  │                      MySQL and PostgreSQL metrics,  │
  │                      query samples, top queries,    │
  │                      query plans, PostgreSQL log    │
  └──────────────┬──────────────────────────────────────┘
                 ▼
          New Relic (US region)
```

## What you will see in New Relic

| Where | What |
|---|---|
| Database Monitoring (DB360) | Both databases: top queries, query plans, query samples, wait events and blocking sessions |
| APM & Services → `db360-demo-app` | One web transaction per URL, database time per transaction, slow SQL, distributed traces |
| Errors Inbox | Errors from `/error`, `/pg/error` and failed logins, tagged with the end user (`enduser.id`) |
| Logs | Application logs linked to traces, host logs and the PostgreSQL server log |
| Infrastructure | Host CPU, memory, disk and network from the collector |

## Prerequisites

- An **Ubuntu 22.04 or 24.04 VM** (amd64 or arm64) with at least 2 vCPUs, 4 GB RAM and 10 GB free disk.
- A user with **`sudo`** on the VM, and outbound internet access (apt, GitHub, Maven Central, `download.newrelic.com`, `otlp.nr-data.net`).
- A **New Relic account in the US region** and its **license key** (an ingest key of type `LICENSE`).
- **git** on the VM: `sudo apt-get update && sudo apt-get install -y git`
- Ports used locally on the VM: 3306 (MySQL), 5432 (PostgreSQL), 8080 (demo app). All databases listen on localhost only.

If your network inspects TLS traffic (for example Cloudflare Gateway or Zscaler), see [Troubleshooting](#troubleshooting) first.

## Repository contents

| File | Purpose |
|---|---|
| `install-mysql.sh` | Installs MySQL, enables the settings DB360 needs, creates the users and loads the demo data |
| `install-postgres.sh` | The same for PostgreSQL |
| `install-nrdot.sh` | Installs the NRDOT collector as a service and configures it |
| `nrdot-config.yaml` | Collector configuration (host, MySQL, PostgreSQL) |
| `install-nr-agent.sh` | Downloads the New Relic Java agent |
| `demo-app/` | The Java demo app (Maven project) |
| `demo-data/` | SQL schemas and seed data for both databases |
| `run-demo-app.sh` | Starts the app with the Java agent attached |
| `load-gen.sh` | Sends a mix of requests to the app |

## Configuration: passwords and keys

Nothing secret is stored in this repository. Each script reads what it needs from environment variables, which you set on the command line for that command. Choose your own passwords and use the **same value** wherever the same variable appears.

| Variable | Used by | Meaning |
|---|---|---|
| `NR_MONITOR_PASSWORD` | `install-mysql.sh`, `install-nrdot.sh` | Password for the MySQL monitoring user `newrelic` |
| `DEMO_DB_PASSWORD` | `install-mysql.sh` | Password for the MySQL app user `demo` |
| `DB_PASSWORD` | `run-demo-app.sh` | The MySQL `demo` password (same value as `DEMO_DB_PASSWORD`) |
| `PG_MONITOR_PASSWORD` | `install-postgres.sh`, `install-nrdot.sh` | Password for the PostgreSQL monitoring role `newrelic` |
| `PG_DEMO_PASSWORD` | `install-postgres.sh` | Password for the PostgreSQL app role `demo` |
| `PG_PASSWORD` | `run-demo-app.sh` | The PostgreSQL `demo` password (same value as `PG_DEMO_PASSWORD`). Optional: without it the PostgreSQL endpoints return 503 |
| `NEW_RELIC_LICENSE_KEY` | `install-nrdot.sh`, `run-demo-app.sh` | Your New Relic license key |

Avoid putting these in `.bashrc` or `.profile`, which would leave them in plain text. Passing them inline, as shown below, only sets them for that one command.

## Installation

Run everything on the VM, in a shell on the cloned repository.

### 1. Get the code

```
git clone https://github.com/beanie999/db360-mysql.git
cd db360-mysql
```

### 2. Install MySQL and load the demo data

```
NR_MONITOR_PASSWORD='<monitor pw>' DEMO_DB_PASSWORD='<demo pw>' ./install-mysql.sh
```

This installs the Ubuntu `mysql-server` package and then:

- turns on the wait-events consumer and sets query text lengths to 4096, which DB360 needs for wait events and query plans;
- creates the `db360` database and two users: `demo` (read/write on `db360`, used by the app) and `newrelic` (read-only monitoring);
- creates a helper procedure that lets the collector get query plans for write statements without write access;
- loads 2,000 customers, 200 products, 50,000 orders and 150,000 order items.

The last lines of output confirm both users can log in. Re-running the script resets the demo tables and passwords.

### 3. Install PostgreSQL and load the demo data

```
PG_DEMO_PASSWORD='<demo pw>' PG_MONITOR_PASSWORD='<monitor pw>' ./install-postgres.sh
```

This installs the Ubuntu `postgresql` package and then:

- enables `pg_stat_statements`, I/O timing, and logging of connections, lock waits and queries slower than 500 ms;
- creates the roles `demo` (app) and `newrelic` (monitoring, with `pg_monitor`) and the `db360` database;
- installs a helper function for query plans on locking and write statements;
- loads 1,000 users (`user1` to `user1000`, password `Passw0rd-<n>`) and 100,000 historical login attempts.

The script restarts PostgreSQL and ends by checking both roles can log in.

### 4. Install the NRDOT collector

```
NEW_RELIC_LICENSE_KEY='<license key>' \
NR_MONITOR_PASSWORD='<MySQL monitor pw>' \
PG_MONITOR_PASSWORD='<PostgreSQL monitor pw>' \
./install-nrdot.sh
```

This downloads the collector package (2.7.1) and verifies its checksum. It copies `nrdot-config.yaml` to `/etc/nrdot-collector/config.yaml`, stores the secrets in the service's environment file (root-only), gives the collector read access to the system and PostgreSQL logs, and starts the service.

Check it is running and healthy:

```
systemctl status nrdot-collector
sudo journalctl -u nrdot-collector -n 50 --no-pager
```

You should see `active (running)` and no errors about authentication or configuration. After a minute or two, host data appears in New Relic under Infrastructure.

### 5. Install Java, Maven and the New Relic Java agent

```
sudo apt-get install -y openjdk-17-jdk maven
./install-nr-agent.sh
```

The agent is unpacked into `~/newrelic-agent`.

### 6. Build the app

```
cd demo-app
mvn -q package
cd ..
```

This produces `demo-app/target/db360-demo.jar`. Maven downloads its dependencies on the first build, so it can take a minute or two.

### 7. Run the app

```
DB_PASSWORD='<MySQL demo pw>' \
PG_PASSWORD='<PostgreSQL demo pw>' \
NEW_RELIC_LICENSE_KEY='<license key>' \
./run-demo-app.sh
```

The app runs in the foreground and logs to the console. Look for `Demo app started on port 8080`. It is named `db360-demo-app` in New Relic; set `NEW_RELIC_APP_NAME` to change that.

Try a few URLs from a second terminal:

```
curl localhost:8080/health
curl localhost:8080/customer?id=42
curl 'localhost:8080/login?user=user42&password=Passw0rd-42'
curl 'localhost:8080/login?user=user42&password=wrong'
```

### 8. Run the load test

In a second terminal on the VM:

```
./load-gen.sh 200
```

The first argument is the number of requests (default 100); an optional second argument is the base URL (default `http://localhost:8080`). Each request prints a line such as:

```
(14 of 200) status 200 /customer
(15 of 200) status 401 /login?user=user7&password=nope&ip=10.1.42.17
```

Failures and slow requests are intentional: the mix includes slow queries, row-lock contention (which takes several seconds), invalid SQL, wrong passwords and repeated attempts that lock an account. Run it a few times, or with a larger number, to give DB360 plenty to show. The collectors report top queries about once a minute, so allow a few minutes for data to appear.

### 9. Check it in New Relic

- **DB360**: open Database Monitoring and select the MySQL and PostgreSQL instances.
- **APM**: open `db360-demo-app` and look at Transactions, Databases and Errors Inbox.
- **Logs**: filter on `entity.name:"db360-demo-app"`.

Handy NRQL queries:

```sql
FROM Transaction SELECT count(*), average(duration) WHERE appName = 'db360-demo-app' FACET name SINCE 30 minutes ago
FROM Log SELECT count(*) WHERE entity.name = 'db360-demo-app' FACET level SINCE 30 minutes ago
FROM Metric SELECT uniques(metricName) WHERE metricName LIKE 'mysql.%' SINCE 30 minutes ago
FROM Metric SELECT uniques(metricName) WHERE metricName LIKE 'postgresql.%' SINCE 30 minutes ago
FROM Transaction SELECT count(*) WHERE appName = 'db360-demo-app' AND error IS true FACET enduser.id SINCE 30 minutes ago
```

## Demo app endpoints

| URL | What it does |
|---|---|
| `/health` | No database access |
| `/customer?id=` | MySQL primary key lookup, then the customer's recent orders |
| `/orders/recent?limit=` | Join and sort on an unindexed column (scan and filesort) |
| `/search?q=` | `LIKE '%…%'` on an unindexed column (full table scan) |
| `/report/sales` | Three-table join with aggregation |
| `/report/status?days=` | Filter and group on unindexed columns |
| `/slow?secs=` | MySQL `SELECT SLEEP()`, capped at 5 seconds |
| `/order` | Write transaction: insert an order and items, then update the total |
| `/lock?id=&secs=` | MySQL row-lock contention: one session holds a lock while the request tries to update the row |
| `/error` | Invalid SQL, producing an error |
| `/login?user=&password=` | PostgreSQL login against the `users` table; every attempt is recorded in `login_audit`. 200 success, 401 wrong password or unknown user, 423 account locked (five failures lock it for 2 minutes) |
| `/login/history?user=` | The user's last 20 login attempts (scans the audit table) |
| `/login/report` | Logins per hour and users with most failures in the last day |
| `/account?user=` | Account overview that reads **both** databases in one request: login security from PostgreSQL (`users`, `login_audit`) and order history from MySQL (`customers`, `orders`). `user42` is matched to customer 42. Each database is read in its own read-only transaction, so it appears as one transaction and trace in APM with both databases in the breakdown |
| `/pg/lock?id=&secs=` | PostgreSQL row-lock contention |
| `/pg/slow?secs=` | PostgreSQL `pg_sleep()` |
| `/pg/dbauth-failure` | A real PostgreSQL authentication failure (wrong password), visible in the PostgreSQL log |
| `/pg/error` | Invalid SQL on PostgreSQL |

Calls without parameters use random users or customers. The app sets the end user on each transaction (`enduser.id`), so errors in the Errors Inbox show who was affected.

## Troubleshooting

**`curl: (60) SSL certificate problem: self-signed certificate in certificate chain`**
Your network is re-signing HTTPS traffic with its own certificate authority, and the VM does not trust it. See who is signing:

```
openssl s_client -showcerts -connect github.com:443 </dev/null 2>/dev/null | grep -E "s:|i:"
```

Export the root CA (from your IT team, or extract the last certificate in that output into a file), then install it:

```
sudo cp corp-ca.crt /usr/local/share/ca-certificates/corp-ca.crt
sudo update-ca-certificates
```

This also updates Java's trust store, which Maven needs. Do this before the other steps.

**Checksum mismatch in `install-nrdot.sh`**
Re-run the script; a truncated download is the usual cause. If it repeats, check for a proxy that alters downloads.

**No logs in New Relic and `SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"` at startup**
You are running an old build. Pull the latest code and rebuild (`mvn -q package`).

**The app starts but says `PG_PASSWORD not set`**
The PostgreSQL endpoints are disabled. Restart it with `PG_PASSWORD` set.

**No query plans or wait events in DB360**
- Query plans need the `newrelic` user to be able to read the demo tables; `install-mysql.sh` and `install-postgres.sh` set this up. Re-run them if you changed users.
- Wait events only appear while a query is actually waiting. Run `curl 'localhost:8080/lock?secs=10'`, and a second call while the first is running.
- Check the settings took effect:
  ```
  sudo mysql -e "SELECT NAME, ENABLED FROM performance_schema.setup_consumers WHERE NAME='events_waits_current'; SHOW VARIABLES LIKE 'performance_schema_max_sql_text_length';"
  ```
  This should show `YES` and `4096`.

**The collector fails or sends nothing**
`sudo journalctl -u nrdot-collector -n 100 --no-pager` shows configuration and login errors. After changing `nrdot-config.yaml`, copy it into place and restart:

```
sudo cp nrdot-config.yaml /etc/nrdot-collector/config.yaml
sudo systemctl restart nrdot-collector
```

**No Java agent data**
Check `~/newrelic-agent/logs/newrelic_agent.log`.

## Cleaning up

```
sudo systemctl disable --now nrdot-collector
sudo apt-get purge -y nrdot-collector mysql-server postgresql
sudo apt-get autoremove -y
```

Purging MySQL and PostgreSQL deletes their data. Remove `~/newrelic-agent` to delete the Java agent.

## Notes

This is a demo, not a hardened setup. The monitoring users can read the demo tables so query plans can be collected, the databases accept password logins on localhost only, and `nrdot-config.yaml` turns off TLS for the local database connections.
