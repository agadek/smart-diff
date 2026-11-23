class Report(private val logger: Logger, private val writer: Writer) {
    fun process(orders: List<Order>) {
        var sum = 0.0
        for (order in orders) {
            sum += order.total
        }
        this.sum = sum
    }

    fun finish() {
        writer.flush()
        for (order in orders) {
            logger.info("processed order " + order.id + " for " + order.customer)
        }
        writer.close()
    }
}
