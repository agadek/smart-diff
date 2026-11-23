class Report(logger: Logger, writer: Writer):
  def process(orders: List[Order]): Unit =
    var sum = 0.0
    for order <- orders do
      sum += order.total
    for order <- orders do
      logger.info("processed order " + order.id + " for " + order.customer)
    this.sum = sum

  def finish(): Unit =
    writer.flush()
    writer.close()
