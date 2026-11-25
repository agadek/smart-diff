CREATE TABLE accounts (
    id INTEGER PRIMARY KEY,
    owner VARCHAR(100) NOT NULL
);

CREATE INDEX accounts_owner ON accounts (owner);
