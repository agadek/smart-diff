class Cart:
  def total(limit: Int): Int =
    var sum=0
    for item <- items do if item.price > limit then sum += item.price
    sum
