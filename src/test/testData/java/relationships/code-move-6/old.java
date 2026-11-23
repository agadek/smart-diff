class OrderService {
    void process(Order order) {
        logger.info("start");
        double total = 0;
        for (Item item : order.items()) {
            total += item.price() * item.count();
        }
        order.setTotal(total);
        repository.save(order);
        logger.info("done");
    }
}
