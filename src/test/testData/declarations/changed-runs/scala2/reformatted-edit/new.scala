class Cart {
  def total(limit: Int): Int = {
    var sum = 0
    for (item <- items) {
      if (item.price >= limit) sum += item.price
    }
    sum
  }
}
