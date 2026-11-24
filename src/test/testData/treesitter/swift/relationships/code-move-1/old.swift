final class OrderService {
    func process(order: Order) throws {
        try store.save(order)
        notifier.send(order.customer, order.id, "processed")
        audit.record(order.id, "processed")
        if order.total <= 0 || order.items.isEmpty {
            throw OrderError.invalid
        }
    }
}
