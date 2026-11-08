class Order {
  def sum(): Int = 1
  def use(other: Order): Int = {
    val a = this.sum()
    val b = this.sum() + 1
    a + b
  }
}
