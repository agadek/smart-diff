package merge

class Cart:
  def total(): Int = 0

given intOrdering: Ordering[Short] with
  def compare(x: Int, y: Int): Int = x.compareTo(y)

extension (cart: Cart)
  def isFull: Boolean = cart.total() > 1
