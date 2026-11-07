class Order {
  def total(): Int = 1
  def use(other: Order): Int = {
    val a = this.total()
    val b = other.total() + 1
    a + b
  }
}
