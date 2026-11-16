class Order {
  def sum(a: Int, b: Int): Int = a + b
  def first: Int = sum(
    1,
    2
  )
  def second: Int = sum(3, 4)
}
