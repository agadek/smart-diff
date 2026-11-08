class Basket {
  def render(name: String, count: Int): String = {
    val text = new StringBuilder()
    heading(name, count, text)
    footer(name, count, text)
    text.toString()
  }

  private def heading(name: String, count: Int, text: StringBuilder): Unit = {
    text.append("Basket: ")
    text.append("owner ").append(name)
    text.append(" qty ").append(count)
  }

  private def footer(name: String, count: Int, text: StringBuilder): Unit = {
    val end = new StringBuilder(" / ")
    end.append("subtotal: ").append(name)
    end.append(" x").append(count).append(" units")
    text.append(end)
  }
}
