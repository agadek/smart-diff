CREATE VIEW totals AS
    SELECT id, sum(amount) AS amount FROM payments GROUP BY id;
