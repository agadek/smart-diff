class OrderService(repository: Repository) {
  def process(order: Order): Unit = {
    audit.log(order)
    var total = 0.0
    for (item <- order.items) {
      total += item.price * item.count
    }
    order.total = total
    repository.save(order)
  }
}
