package merge

class Cart:
  def total(): Int = 0

given intOrdering: Ordering[Int] with
  def compare(x: Int, y: Int): Int = x - y

extension (cart: Cart)
  def isFull: Boolean = cart.total() > 0
