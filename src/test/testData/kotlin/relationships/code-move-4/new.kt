class OrderService(private val repository: Repository) {
    fun process(order: Order) {
        if (order.customer == null || order.items.isEmpty()) {
            throw IllegalArgumentException("invalid order " + order.id)
        }
        var total = 0.0
        for (item in order.items) {
            total += item.price * item.count
        }
        order.total = total
        repository.save(order)
        if (order.customer == null || order.items.isEmpty()) {
            throw IllegalArgumentException("invalid order " + order.id)
        }
    }
}
