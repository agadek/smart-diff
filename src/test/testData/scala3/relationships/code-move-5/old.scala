class OrderService(repository: Repository):
  def process(order: Order): Unit =
    var total = 0.0
    for item <- order.items do
      total += item.price * item.count
    order.total = total
    repository.save(order)
    if order.customer == null || order.items.isEmpty then
      throw IllegalArgumentException("invalid order " + order.id)
