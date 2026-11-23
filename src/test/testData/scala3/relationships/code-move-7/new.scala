class OrderService(repository: Repository):
  def process(order: Order): Unit =
    validate(order)
    var total = 0.0
    for item <- order.items do
      total += item.price * item.count
    order.total = total
    repository.save(order)

  private def validate(order: Order): Unit =
    if order.customer == null || order.items.isEmpty then
      throw IllegalArgumentException("invalid order " + order.id)
