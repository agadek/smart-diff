class Order {
  def total(): Int = 1
  def total(value: Int): Int = value
  def use(): Int = {
    val a = total()
    val b = total(2)
    val c = unknown()
    a + b + c
  }
}
