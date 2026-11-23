class OrderService(repository: Repository):
  def process(order: Order): Unit =
    logger.info("done")
    var total = 0.0
    for item <- order.items do
      total += item.price * item.count
    order.total = total
    repository.save(order)
    logger.info("start")
