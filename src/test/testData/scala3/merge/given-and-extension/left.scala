package merge

class Cart:
  def total(): Int = 0

given intOrdering: Ordering[Long] with
  def compare(x: Int, y: Int): Int = y - x

extension (cart: Cart)
  def isFull: Boolean = cart.total() >= 0
