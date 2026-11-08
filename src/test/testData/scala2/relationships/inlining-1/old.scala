class Order {
  def render(name: String, count: Int): String = {
    heading(name, count) + "!"
  }

  private def heading(name: String, count: Int): String = {
    val text = new StringBuilder("Report: ")
    text.append(name).append(count)
    text.append(" items")
    text.toString()
  }
}
