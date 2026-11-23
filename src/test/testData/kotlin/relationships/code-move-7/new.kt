class OrderService(private val repository: Repository) {
    fun process(order: Order) {
        validate(order)
        var total = 0.0
        for (item in order.items) {
            total += item.price * item.count
        }
        order.total = total
        repository.save(order)
    }

    private fun validate(order: Order) {
        if (order.customer == null || order.items.isEmpty()) {
            throw IllegalArgumentException("invalid order " + order.id)
        }
    }
}
