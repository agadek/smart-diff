class Invoice {
  def compute(x: Int): Int = {
    val doubled = x * 2
    val plus = doubled + 1
    val adjusted = plus * 2
    val tripled = x * 3
    val minus = tripled - 1
    val trimmed = minus / 2
    adjusted + trimmed
  }
}
