class Report(logger: Logger, writer: Writer) {
  def process(orders: List[Order]): Unit = {
    var sum = 0.0
    for (order <- orders) {
      sum += order.total
    }
    this.sum = sum
  }

  def finish(): Unit = {
    writer.flush()
    for (order <- orders) {
      logger.info("processed order " + order.id + " for " + order.customer)
    }
    writer.close()
  }
}
