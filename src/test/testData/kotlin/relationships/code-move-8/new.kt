class OrderService(private val repository: Repository) {
    fun process(order: Order) {
        audit.log(order)
        var total = 0.0
        for (item in order.items) {
            total += item.price * item.count
        }
        order.total = total
        repository.save(order)
    }
}
