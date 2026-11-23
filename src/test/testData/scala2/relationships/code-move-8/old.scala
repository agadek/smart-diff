class OrderService(repository: Repository) {
  def process(order: Order): Unit = {
    var total = 0.0
    for (item <- order.items) {
      total += item.price * item.count
    }
    order.total = total
    repository.save(order)
    audit.log(order)
  }
}
