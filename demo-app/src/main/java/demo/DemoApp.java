package demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Small demo web app: each URL runs one or more SQL queries against the db360 demo database.
 *
 * Config (environment): DB_URL, DB_USER, DB_PASSWORD (required), PORT.
 */
public class DemoApp {

    interface Handler {
        String handle(Map<String, String> params) throws Exception;
    }

    private static HikariDataSource ds;

    public static void main(String[] args) throws IOException {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isEmpty()) {
            System.err.println("DB_PASSWORD must be set");
            System.exit(1);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("DB_URL", "jdbc:mysql://localhost:3306/db360"));
        cfg.setUsername(env("DB_USER", "demo"));
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(10);
        ds = new HikariDataSource(cfg);

        int port = Integer.parseInt(env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));

        // No DB access.
        route(server, "/health", p -> "{\"status\":\"ok\"}");

        // Primary key lookup, then the customer's recent orders (indexed on customer_id).
        route(server, "/customer", p -> {
            int id = intParam(p, "id", 1 + ThreadLocalRandom.current().nextInt(2000));
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
        route(server, "/order", p -> placeOrder(
            intParam(p, "customer", 1 + ThreadLocalRandom.current().nextInt(2000))));

        // Invalid SQL, to produce an error.
        route(server, "/error", p -> query("SELECT * FROM table_that_does_not_exist"));

        server.start();
        System.out.println("Listening on port " + port);
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
                return "{\"orderId\":" + orderId + ",\"items\":" + items + "}";
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Runs a query and returns the rows as a JSON array. */
    private static String query(String sql, Object... args) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return toJson(rs);
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
                else if (v instanceof Number) sb.append(v);
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
            int status = 200;
            String body;
            try {
                body = h.handle(parseQuery(ex.getRequestURI().getRawQuery()));
            } catch (Exception e) {
                status = 500;
                body = "{\"error\":\"" + escape(String.valueOf(e.getMessage())) + "\"}";
            }
            send(ex, status, body);
        });
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
