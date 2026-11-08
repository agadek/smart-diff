class Order {
  def sum(): Int = 1
  def total(value: Int): Int = value
  def use(): Int = {
    val a = sum()
    val b = total(2)
    val c = unknown()
    a + b + c
  }
}
