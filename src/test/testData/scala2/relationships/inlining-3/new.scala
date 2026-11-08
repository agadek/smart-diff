class Basket {
  def render(name: String, count: Int): String = {
    val text = new StringBuilder()
    text.append("Basket: ")
    text.append("owner ").append(name)
    text.append(" qty ").append(count)
    val end = new StringBuilder(" / ")
    end.append("subtotal: ").append(name)
    end.append(" x").append(count).append(" units")
    text.append(end)
    text.toString()
  }
}
