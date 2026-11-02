class Invoice {
  def first(x: Int): Int = {
    doubled(x) + 1
  }
  def second(x: Int): Int = {
    doubled(x) * 3
  }
  def doubled(x: Int): Int = {
    val doubled = x * 2
    val result = doubled + 1
    result
  }
}
