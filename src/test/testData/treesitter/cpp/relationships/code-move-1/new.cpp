class OrderService {
public:
    void process(const Order& order) {
        if (order.total <= 0 || order.items.empty()) {
            throw std::invalid_argument("invalid order");
        }
        store.save(order);
        notifier.send(order.customer, order.id, "processed");
        audit.record(order.id, "processed");
    }
};
