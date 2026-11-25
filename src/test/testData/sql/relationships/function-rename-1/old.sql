CREATE FUNCTION order_total(order_id INTEGER) RETURNS NUMERIC AS $$
BEGIN
    RETURN (SELECT sum(total) FROM orders WHERE id = order_id);
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION order_count(customer INTEGER) RETURNS INTEGER AS $$
BEGIN
    RETURN (SELECT count(*) FROM orders WHERE customer_id = customer);
END;
$$ LANGUAGE plpgsql;
