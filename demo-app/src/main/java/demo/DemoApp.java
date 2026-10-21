package demo;

import com.newrelic.api.agent.ExtendedRequest;
import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Response;
import com.newrelic.api.agent.Transaction;
import com.newrelic.api.agent.TransactionNamePriority;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * Small demo web app: each URL runs one or more SQL queries against the db360 demo database.
 *
 * Config (environment): DB_URL, DB_USER, DB_PASSWORD (required) for MySQL; PG_URL, PG_USER, PG_PASSWORD
 * for PostgreSQL (optional, the /login and /pg endpoints return 503 without PG_PASSWORD); PORT.
 */
public class DemoApp {

    interface Handler {
        String handle(Map<String, String> params) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(DemoApp.class);
    private static final long SLOW_QUERY_MS = 500;

    private static HikariDataSource ds;
    private static HikariDataSource pgDs;
    private static String pgUrl;
    private static String pgUser;

    /** Thrown by a handler to return a specific HTTP status; the message is the JSON body. */
    static class HttpError extends Exception {
        final int status;

        HttpError(int status, String json) {
            super(json);
            this.status = status;
        }
    }

    public static void main(String[] args) throws IOException {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isEmpty()) {
            log.error("DB_PASSWORD must be set");
            System.exit(1);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("DB_URL", "jdbc:mysql://localhost:3306/db360"));
        cfg.setUsername(env("DB_USER", "demo"));
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(10);
        ds = new HikariDataSource(cfg);

        String pgPassword = System.getenv("PG_PASSWORD");
        if (pgPassword == null || pgPassword.isEmpty()) {
            log.warn("PG_PASSWORD not set, PostgreSQL endpoints are disabled");
        } else {
            pgUrl = env("PG_URL", "jdbc:postgresql://localhost:5432/db360");
            pgUser = env("PG_USER", "demo");
            HikariConfig pgCfg = new HikariConfig();
            pgCfg.setJdbcUrl(pgUrl);
            pgCfg.setUsername(pgUser);
            pgCfg.setPassword(pgPassword);
            pgCfg.setMaximumPoolSize(10);
            pgDs = new HikariDataSource(pgCfg);
        }

        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));

        // No DB access.
        route(server, "/health", p -> "{\"status\":\"ok\"}");

        // Primary key lookup, then the customer's recent orders (indexed on customer_id).
        route(server, "/customer", p -> {
            int id = intParam(p, "id", 1 + ThreadLocalRandom.current().nextInt(2000));
            identify("customer-" + id);
            String customer = query(
                "SELECT id, name, email, country FROM customers WHERE id = ?", id);
            String orders = query(
                "SELECT id, status, total, created_at FROM orders WHERE customer_id = ? "
                    + "ORDER BY id DESC LIMIT 5", id);
            return "{\"customer\":" + customer + ",\"recentOrders\":" + orders + "}";
        });

        // Join + ORDER BY on an unindexed column (created_at): scan and filesort.
        route(server, "/orders/recent", p -> query(
            "SELECT o.id, o.status, o.total, o.created_at, c.name "
                + "FROM orders o JOIN customers c ON c.id = o.customer_id "
                + "ORDER BY o.created_at DESC LIMIT ?", intParam(p, "limit", 20)));

        // Leading-wildcard LIKE on an unindexed column: full table scan.
        route(server, "/search", p -> query(
            "SELECT id, name, email FROM customers WHERE email LIKE ? LIMIT 25",
            "%" + p.getOrDefault("q", "customer" + (1 + ThreadLocalRandom.current().nextInt(2000))) + "%"));

        // Three-table join and aggregation; order_items.product_id is unindexed.
        route(server, "/report/sales", p -> query(
            "SELECT p.category, COUNT(*) AS items, SUM(i.quantity * i.unit_price) AS revenue "
                + "FROM order_items i "
                + "JOIN products p ON p.id = i.product_id "
                + "JOIN orders o ON o.id = i.order_id "
                + "WHERE o.status <> 'CANCELLED' "
                + "GROUP BY p.category ORDER BY revenue DESC"));

        // Filter on an unindexed column (status) with a date range.
        route(server, "/report/status", p -> query(
            "SELECT status, COUNT(*) AS orders, ROUND(AVG(total), 2) AS avg_total "
                + "FROM orders WHERE created_at > NOW() - INTERVAL ? DAY GROUP BY status",
            intParam(p, "days", 30)));

        // Deliberately slow statement.
        route(server, "/slow", p -> {
            double secs = Math.min(5, Double.parseDouble(p.getOrDefault("secs", "1.5")));
            return query("SELECT SLEEP(?) AS slept, COUNT(*) AS customers FROM customers", secs);
        });

        // Write transaction: insert an order and items, then update the order total.
        route(server, "/order", p -> {
            int customerId = intParam(p, "customer", 1 + ThreadLocalRandom.current().nextInt(2000));
            identify("customer-" + customerId);
            return placeOrder(customerId);
        });

        // Row lock contention: one session holds a lock on a product row while this request tries to update it.
        route(server, "/lock", p -> lockContention(ds,
            "SELECT id FROM products WHERE id = ? FOR UPDATE",
            "UPDATE products SET price = price WHERE id = ?",
            intParam(p, "id", 1), Math.min(15, intParam(p, "secs", 5))));

        // --- PostgreSQL ---

        // Application login against the users table; every attempt is written to login_audit.
        // 200 success, 401 bad password or unknown user, 423 account locked.
        route(server, "/login", DemoApp::login);

        // Last logins for one user; login_audit has no username index, so this scans the table.
        route(server, "/login/history", p -> query(pg(),
            "SELECT id, success, reason, source_ip, created_at FROM login_audit "
                + "WHERE username = ? ORDER BY created_at DESC LIMIT 20",
            p.getOrDefault("user", "user" + (1 + ThreadLocalRandom.current().nextInt(1000)))));

        // Login outcomes per hour and the users with most failures over the last day.
        route(server, "/login/report", p -> {
            String byHour = query(pg(),
                "SELECT date_trunc('hour', created_at) AS hour, "
                    + "count(*) FILTER (WHERE success) AS succeeded, "
                    + "count(*) FILTER (WHERE NOT success) AS failed "
                    + "FROM login_audit WHERE created_at > now() - interval '24 hours' "
                    + "GROUP BY 1 ORDER BY 1 DESC");
            String offenders = query(pg(),
                "SELECT username, count(*) AS failures FROM login_audit "
                    + "WHERE NOT success AND created_at > now() - interval '24 hours' "
                    + "GROUP BY username ORDER BY failures DESC LIMIT 10");
            return "{\"byHour\":" + byHour + ",\"topFailures\":" + offenders + "}";
        });

        // Row lock contention on a users row.
        route(server, "/pg/lock", p -> lockContention(pg(),
            "SELECT id FROM users WHERE id = ? FOR UPDATE",
            "UPDATE users SET email = email WHERE id = ?",
            intParam(p, "id", 1), Math.min(15, intParam(p, "secs", 5))));

        // Deliberately slow statement.
        route(server, "/pg/slow", p -> {
            double secs = Math.min(5, Double.parseDouble(p.getOrDefault("secs", "1.5")));
            return query(pg(), "SELECT pg_sleep(?) AS slept, count(*) AS users FROM users", secs);
        });

        // A real database-level authentication failure (wrong password for the demo role).
        route(server, "/pg/dbauth-failure", p -> {
            pg();
            try (Connection c = DriverManager.getConnection(pgUrl, pgUser, "not-the-password")) {
                return "{\"unexpected\":\"connected\"}";
            } catch (SQLException e) {
                log.warn("Database authentication failed for role {}: {}", pgUser, e.getMessage());
                throw new HttpError(401, "{\"error\":\"database authentication failed\"}");
            }
        });

        // Invalid SQL, to produce an error.
        route(server, "/pg/error", p -> {
            identify("user" + (1 + ThreadLocalRandom.current().nextInt(1000)));
            return query(pg(), "SELECT * FROM table_that_does_not_exist");
        });

        // Invalid SQL, to produce an error.
        route(server, "/error", p -> {
            identify("customer-" + (1 + ThreadLocalRandom.current().nextInt(2000)));
            return query("SELECT * FROM table_that_does_not_exist");
        });

        server.start();
        log.info("Demo app started on port {}, database {}", port, cfg.getJdbcUrl());
    }

    private static String placeOrder(int customerId) throws SQLException {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                long orderId;
                try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO orders (customer_id, status, total, created_at) VALUES (?, 'NEW', 0, NOW())",
                    Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, customerId);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        orderId = keys.getLong(1);
                    }
                }
                int items = 1 + rnd.nextInt(4);
                for (int i = 0; i < items; i++) {
                    try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO order_items (order_id, product_id, quantity, unit_price) "
                            + "SELECT ?, id, ?, price FROM products WHERE id = ?")) {
                        ps.setLong(1, orderId);
                        ps.setInt(2, 1 + rnd.nextInt(5));
                        ps.setInt(3, 1 + rnd.nextInt(200));
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE orders SET total = (SELECT COALESCE(SUM(quantity * unit_price), 0) "
                        + "FROM order_items WHERE order_id = ?) WHERE id = ?")) {
                    ps.setLong(1, orderId);
                    ps.setLong(2, orderId);
                    ps.executeUpdate();
                }
                c.commit();
                log.info("Order {} placed for customer {} with {} items", orderId, customerId, items);
                return "{\"orderId\":" + orderId + ",\"items\":" + items + "}";
            } catch (SQLException e) {
                log.warn("Order for customer {} failed, rolling back", customerId, e);
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * Starts a background session that locks one row for {@code secs} seconds, then updates the
     * same row from this request, which blocks until the lock is released.
     */
    private static String lockContention(DataSource src, String lockSql, String updateSql, int rowId, int secs)
            throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (Connection c = src.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(lockSql)) {
                    ps.setInt(1, rowId);
                    ps.executeQuery().close();
                }
                log.info("Holding row lock on row {} for {} s", rowId, secs);
                locked.countDown();
                Thread.sleep(secs * 1000L);
                c.rollback();
                log.info("Released row lock on row {}", rowId);
            } catch (Exception e) {
                log.error("Lock holder for row {} failed", rowId, e);
            } finally {
                locked.countDown();
            }
        }, "lock-holder");
        holder.setDaemon(true);
        holder.start();
        locked.await(5, TimeUnit.SECONDS);

        long start = System.nanoTime();
        try (Connection c = src.getConnection(); PreparedStatement ps = c.prepareStatement(updateSql)) {
            ps.setInt(1, rowId);
            ps.executeUpdate();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.warn("Update of row {} waited {} ms for a row lock", rowId, ms);
        return "{\"rowId\":" + rowId + ",\"waitedMs\":" + ms + "}";
    }

    /** Sets the end user on the current transaction; it shows as enduser.id on traces and in the Errors inbox. */
    private static void identify(String userId) {
        NewRelic.setUserId(userId);
    }

    private static DataSource pg() throws HttpError {
        if (pgDs == null) {
            throw new HttpError(503, "{\"error\":\"PostgreSQL is not configured (PG_PASSWORD not set)\"}");
        }
        return pgDs;
    }

    /**
     * Checks a username and password in PostgreSQL (the hash is verified with pgcrypto's crypt()),
     * updates the failure counter and lockout, and records the attempt in login_audit.
     */
    private static String login(Map<String, String> p) throws Exception {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int n = 1 + rnd.nextInt(1000);
        String user = p.getOrDefault("user", "user" + n);
        String password = p.getOrDefault("password", rnd.nextInt(100) < 80 ? "Passw0rd-" + n : "wrong-password");
        String ip = p.getOrDefault("ip", p.getOrDefault("_remote", "unknown"));
        identify(user);
        DataSource src = pg();

        String result;
        try (Connection c = src.getConnection()) {
            c.setAutoCommit(false);
            try {
                long id = -1;
                boolean passwordOk = false;
                boolean locked = false;
                boolean hadLock = false;
                int attempts = 0;
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, password_hash = crypt(?, password_hash) AS ok, failed_attempts, "
                        + "(locked_until IS NOT NULL AND locked_until > now()) AS locked, "
                        + "(locked_until IS NOT NULL) AS had_lock FROM users WHERE username = ?")) {
                    ps.setString(1, password);
                    ps.setString(2, user);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            id = rs.getLong("id");
                            passwordOk = rs.getBoolean("ok");
                            attempts = rs.getInt("failed_attempts");
                            locked = rs.getBoolean("locked");
                            hadLock = rs.getBoolean("had_lock");
                        }
                    }
                }

                if (id < 0) {
                    result = "unknown_user";
                } else if (locked) {
                    result = "account_locked";
                } else if (passwordOk) {
                    result = "success";
                    try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET failed_attempts = 0, locked_until = NULL, last_login = now() WHERE id = ?")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                } else {
                    result = "bad_password";
                    // A lock that has expired starts the count again.
                    int failures = (hadLock ? 0 : attempts) + 1;
                    try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET failed_attempts = ?, "
                            + "locked_until = CASE WHEN ?::int >= 5 THEN now() + interval '2 minutes' ELSE NULL END "
                            + "WHERE id = ?")) {
                        ps.setInt(1, failures);
                        ps.setInt(2, failures);
                        ps.setLong(3, id);
                        ps.executeUpdate();
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO login_audit (username, success, reason, source_ip) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, user);
                    ps.setBoolean(2, "success".equals(result));
                    ps.setString(3, "success".equals(result) ? null : result);
                    ps.setString(4, ip);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }

        NewRelic.addCustomParameter("login.result", result);
        if ("success".equals(result)) {
            log.info("Login succeeded for user {} from {}", user, ip);
            return "{\"login\":\"success\",\"user\":\"" + escape(user) + "\"}";
        }
        log.warn("Login failed for user {} from {}: {}", user, ip, result);
        int status = "account_locked".equals(result) ? 423 : 401;
        throw new HttpError(status, "{\"login\":\"failed\",\"reason\":\"" + result + "\"}");
    }

    /** Runs a query and returns the rows as a JSON array. */
    private static String query(String sql, Object... args) throws SQLException {
        return query(ds, sql, args);
    }

    private static String query(DataSource src, String sql, Object... args) throws SQLException {
        long start = System.nanoTime();
        try (Connection c = src.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                String json = toJson(rs);
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (ms >= SLOW_QUERY_MS) {
                    log.warn("Slow query took {} ms: {}", ms, sql);
                } else {
                    log.debug("Query took {} ms: {}", ms, sql);
                }
                return json;
            }
        }
    }

    private static String toJson(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        StringBuilder sb = new StringBuilder("[");
        boolean firstRow = true;
        while (rs.next()) {
            if (!firstRow) sb.append(',');
            firstRow = false;
            sb.append('{');
            for (int i = 1; i <= md.getColumnCount(); i++) {
                if (i > 1) sb.append(',');
                sb.append('"').append(escape(md.getColumnLabel(i))).append("\":");
                Object v = rs.getObject(i);
                if (v == null) sb.append("null");
                else if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append('"').append(escape(v.toString())).append('"');
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void route(HttpServer server, String path, Handler h) {
        server.createContext(path, ex -> {
            long start = System.nanoTime();
            Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
            params.put("_remote", ex.getRemoteAddress().getAddress().getHostAddress());
            nameTransaction(ex, path, params);
            int status = 200;
            String body;
            try {
                body = h.handle(params);
            } catch (HttpError e) {
                status = e.status;
                body = e.getMessage();
            } catch (Exception e) {
                status = 500;
                body = "{\"error\":\"" + escape(String.valueOf(e.getMessage())) + "\"}";
                log.error("Request {} failed", path, e);
                NewRelic.noticeError(e);
            }
            NewRelic.getAgent().getTransaction().setWebResponse(new WebResponse(ex, status));
            send(ex, status, body);
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} -> {} in {} ms", ex.getRequestMethod(), ex.getRequestURI(), status, ms);
        });
    }

    /**
     * The agent's built-in JDK HTTP server instrumentation reports every request as one
     * non-web transaction. Mark it as a web transaction and name it after the URL path.
     */
    private static void nameTransaction(HttpExchange ex, String path, Map<String, String> params) {
        Transaction txn = NewRelic.getAgent().getTransaction();
        txn.convertToWebTransaction();
        txn.setWebRequest(new WebRequest(ex, params));
        txn.setTransactionName(TransactionNamePriority.CUSTOM_HIGH, true, "Url", path.substring(1));
    }

    private static class WebRequest extends ExtendedRequest {
        private final HttpExchange ex;
        private final Map<String, String> params;

        WebRequest(HttpExchange ex, Map<String, String> params) {
            this.ex = ex;
            this.params = params;
        }

        @Override public String getMethod() { return ex.getRequestMethod(); }
        @Override public String getRequestURI() { return ex.getRequestURI().getPath(); }
        @Override public String getRemoteUser() { return null; }
        @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(params.keySet()); }
        @Override public String[] getParameterValues(String name) {
            String v = params.get(name);
            return v == null ? null : new String[] {v};
        }
        @Override public Object getAttribute(String name) { return null; }
        @Override public String getCookieValue(String name) { return null; }
        @Override public HeaderType getHeaderType() { return HeaderType.HTTP; }
        @Override public String getHeader(String name) { return ex.getRequestHeaders().getFirst(name); }
        @Override public List<String> getHeaders(String name) { return ex.getRequestHeaders().get(name); }
    }

    private static class WebResponse implements Response {
        private final HttpExchange ex;
        private final int status;

        WebResponse(HttpExchange ex, int status) {
            this.ex = ex;
            this.status = status;
        }

        @Override public int getStatus() { return status; }
        @Override public String getStatusMessage() { return status == 200 ? "OK" : "Error"; }
        @Override public String getContentType() { return "application/json"; }
        @Override public HeaderType getHeaderType() { return HeaderType.HTTP; }
        @Override public void setHeader(String name, String value) { ex.getResponseHeaders().set(name, value); }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                      java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static int intParam(Map<String, String> p, String name, int dflt) {
        String v = p.get(name);
        return v == null ? dflt : Integer.parseInt(v);
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? dflt : v;
    }
}
