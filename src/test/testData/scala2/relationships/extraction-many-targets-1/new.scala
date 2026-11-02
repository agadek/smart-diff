class Order {
  def compute(x: Int): Int = {
    doubled(x) + trimmed(x)
  }
  def doubled(x: Int): Int = {
    val doubled = x * 2
    val plus = doubled + 1
    plus * 2
  }
  def trimmed(x: Int): Int = {
    val tripled = x * 3
    val minus = tripled - 1
    minus / 2
  }
}
