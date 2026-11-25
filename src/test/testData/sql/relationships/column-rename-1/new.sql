CREATE TABLE customers (
    id INTEGER PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email_address VARCHAR(200),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    total NUMERIC(10, 2) DEFAULT 0
);
