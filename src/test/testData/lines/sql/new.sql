UPDATE settings SET value = 'on' WHERE key = 'feature';
DELETE FROM sessions WHERE expired = 1;
INSERT INTO audit (message) VALUES ('cleanup started');
INSERT INTO audit (message) VALUES ('cleanup finished');
SELECT id, name FROM customers WHERE active = 1;
SELECT id, total FROM orders WHERE created > '2026-01-01';
SELECT id, amount FROM invoices WHERE paid = 0;
