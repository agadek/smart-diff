-- Shop schema.
CREATE TABLE customers (
    id INTEGER PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(200)
);

-- Orders placed by customers.
CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    -- The customer who placed it.
    customer_id INTEGER NOT NULL REFERENCES customers (id),
    total NUMERIC(10, 2) DEFAULT 0,
    note VARCHAR(200) DEFAULT 'none',
    CONSTRAINT orders_total_positive CHECK (total >= 0)
);

CREATE INDEX orders_customer ON orders (customer_id);

CREATE VIEW big_orders AS
    SELECT id, total FROM orders WHERE total > 100;

CREATE FUNCTION order_total(order_id BIGINT) RETURNS NUMERIC AS $$
BEGIN
    RETURN (SELECT total FROM orders WHERE id = order_id);
END;
$$ LANGUAGE plpgsql;

INSERT INTO customers (id, name) VALUES (1, 'Ada');
