-- Demo schema for the DB360 demo. Some columns are deliberately left unindexed
-- (customers.email, products.category, orders.status/created_at, order_items.product_id)
-- so that a few of the demo queries do full scans and show up in DB360.
CREATE DATABASE IF NOT EXISTS db360;
USE db360;

DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS customers;

CREATE TABLE customers (
  id         INT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(100) NOT NULL,
  email      VARCHAR(150) NOT NULL,
  country    CHAR(2)      NOT NULL,
  created_at DATETIME     NOT NULL
);

CREATE TABLE products (
  id       INT AUTO_INCREMENT PRIMARY KEY,
  name     VARCHAR(100)  NOT NULL,
  category VARCHAR(50)   NOT NULL,
  price    DECIMAL(10,2) NOT NULL
);

CREATE TABLE orders (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT           NOT NULL,
  status      VARCHAR(20)   NOT NULL,
  total       DECIMAL(12,2) NOT NULL DEFAULT 0,
  created_at  DATETIME      NOT NULL,
  KEY idx_orders_customer (customer_id)
);

CREATE TABLE order_items (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id   INT           NOT NULL,
  product_id INT           NOT NULL,
  quantity   INT           NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL DEFAULT 0,
  KEY idx_items_order (order_id)
);
