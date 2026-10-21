package shop;

import java.util.ArrayList;
import java.util.List;

public class OrderService {
    private final OrderRepository repository;
    private final List<String> audit = new ArrayList<>();

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    /** Places an order and records it. */
    public Order placeOrder(String customer, List<Item> items) {
        validate(items);
        Order order = new Order(customer, items);
        repository.save(order);
        audit.add("placed " + order.id());
        return order;
    }

    public void cancelOrder(String id) {
        Order order = repository.find(id);
        order.cancel();
        audit.add("cancelled " + id);
    }

    public int total(Order order) {
        int sum = 0;
        for (Item item : order.items()) {
            sum += item.price() * item.quantity();
        }
        return sum;
    }

    public int applyDiscount(Order order, double percent) {
        return (int) (total(order) * (1 - percent / 100));
    }

    private String formatReceipt(Order order) {
        return order.customer() + ": " + total(order);
    }

    private void validate(List<Item> items) {
        if (items.isEmpty()) throw new IllegalArgumentException("no items");
    }
}

class OrderRepository {
    private final List<Order> orders = new ArrayList<>();

    Order find(String id) {
        for (Order order : orders) {
            if (order.id().equals(id)) return order;
        }
        return null;
    }

    void save(Order order) {
        orders.add(order);
    }
}
