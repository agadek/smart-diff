package shop;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class OrderService
{
    private final OrderRepository repository;
    private final List<String> audit = new ArrayList<>();

    public OrderService(OrderRepository repository)
    {
        this.repository = repository;
    }

    /** Places an order, saves it and records it in the audit log. */
    public Order placeOrder(String customer, List<Item> items)
    {
        repository.validate(items);
        Order order = new Order(customer, items);
        repository.save(order);
        audit.add("placed " + order.id());
        return order;
    }

    public BigDecimal applyDiscount(Order order, BigDecimal percent)
    {
        BigDecimal factor = BigDecimal.ONE.subtract(percent.movePointLeft(2));
        return BigDecimal.valueOf(total(order)).multiply(factor);
    }

    public void cancelOrder(String id)
    {
        Order order = repository.find(id);
        if (order == null) throw new IllegalArgumentException("unknown order " + id);
        order.cancel();
        audit.add("cancelled " + id);
    }

    public void refund(String id)
    {
        cancelOrder(id);
        audit.add("refunded " + id);
    }

    public int total(Order order)
    {
        int sum = 0;
        for (Item item : order.items())
        {
            sum += item.price() * item.quantity();
        }
        return sum;
    }

    public String summary(Order order)
    {
        StringBuilder text = new StringBuilder(order.customer());
        describeItems(order, text);
        text.append("\nTotal: ").append(total(order));
        return text.toString();
    }

    private void describeItems(Order order, StringBuilder text)
    {
        for (Item item : order.items())
        {
            text.append("\n  ").append(item.name()).append(" x").append(item.quantity());
        }
    }

    public int shippingCost(Order order)
    {
        return ShippingRates.costFor(order);
    }
}

class OrderRepository
{
    private final List<Order> orders = new ArrayList<>();

    Order find(String id)
    {
        for (Order order : orders)
        {
            if (order.id().equals(id)) return order;
        }
        return null;
    }

    void save(Order order)
    {
        orders.add(order);
    }

    void validate(List<Item> items)
    {
        if (items.isEmpty()) throw new IllegalArgumentException("no items");
    }
}

final class ShippingRates
{
    private ShippingRates()
    {
    }

    static int costFor(Order order)
    {
        int weight = 0;
        for (Item item : order.items())
        {
            weight += item.weight() * item.quantity();
        }
        return weight > 10 ? 0 : 5 + weight / 2;
    }
}
