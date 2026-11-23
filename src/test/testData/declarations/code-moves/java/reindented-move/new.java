class OrderService {
    void process(Order order) {
        if (order.customer() == null || order.items().isEmpty()) {
                throw new IllegalArgumentException("invalid order " + order.id());
        }
        double total = 0;
        for (Item item : order.items()) {
            total += item.price() * item.count();
        }
        order.setTotal(total);
        repository.save(order);
    }
}
