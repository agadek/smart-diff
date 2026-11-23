class OrderService(repository: Repository) {
  def process(order: Order): Unit = {
    if (order.customer == null || order.items.isEmpty) {
      throw new IllegalArgumentException("order is invalid: " + order.id)
    }
    var total = 0.0
    for (item <- order.items) {
      total += item.price * item.count
    }
    order.total = total
    repository.save(order)
  }
}
