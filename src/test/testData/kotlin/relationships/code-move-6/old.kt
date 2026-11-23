class OrderService(private val repository: Repository) {
    fun process(order: Order) {
        logger.info("start")
        var total = 0.0
        for (item in order.items) {
            total += item.price * item.count
        }
        order.total = total
        repository.save(order)
        logger.info("done")
    }
}
