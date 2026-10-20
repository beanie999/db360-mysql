-- Seed data: 2,000 customers, 200 products, 50,000 orders, 150,000 order items.
USE db360;
SET SESSION cte_max_recursion_depth = 100000;

INSERT INTO customers (name, email, country, created_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 2000
)
SELECT CONCAT('Customer ', n),
       CONCAT('customer', n, '@example.com'),
       ELT(1 + FLOOR(RAND() * 5), 'US', 'GB', 'DE', 'FR', 'JP'),
       NOW() - INTERVAL FLOOR(RAND() * 730) DAY
FROM seq;

INSERT INTO products (name, category, price)
WITH RECURSIVE seq AS (
  SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 200
)
SELECT CONCAT('Product ', n),
       ELT(1 + MOD(n, 6), 'Books', 'Electronics', 'Garden', 'Toys', 'Clothing', 'Grocery'),
       ROUND(5 + RAND() * 195, 2)
FROM seq;

INSERT INTO orders (customer_id, status, total, created_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 50000
)
SELECT 1 + FLOOR(RAND() * 2000),
       ELT(1 + FLOOR(RAND() * 4), 'NEW', 'PAID', 'SHIPPED', 'CANCELLED'),
       0,
       NOW() - INTERVAL FLOOR(RAND() * 365 * 24 * 60) MINUTE
FROM seq;

-- Three items per order.
INSERT INTO order_items (order_id, product_id, quantity)
SELECT o.id, 1 + FLOOR(RAND() * 200), 1 + FLOOR(RAND() * 5)
FROM orders o
JOIN (SELECT 1 AS k UNION ALL SELECT 2 UNION ALL SELECT 3) k;

UPDATE order_items i JOIN products p ON p.id = i.product_id
SET i.unit_price = p.price;

UPDATE orders o
JOIN (SELECT order_id, SUM(quantity * unit_price) AS t FROM order_items GROUP BY order_id) s
  ON s.order_id = o.id
SET o.total = s.t;
