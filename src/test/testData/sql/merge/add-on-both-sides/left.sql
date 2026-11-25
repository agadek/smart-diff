SET search_path TO shop;

CREATE TABLE accounts (
    id INTEGER PRIMARY KEY,
    owner VARCHAR(100) NOT NULL,
    balance NUMERIC(12, 2) DEFAULT 0
);

CREATE VIEW rich_accounts AS
    SELECT id, owner FROM accounts WHERE balance > 1000;

CREATE FUNCTION fee(amount NUMERIC) RETURNS NUMERIC AS $$
BEGIN
    RETURN amount * 0.01;
END;
$$ LANGUAGE plpgsql;

CREATE INDEX accounts_owner ON accounts (owner);

CREATE INDEX accounts_balance ON accounts (balance);
