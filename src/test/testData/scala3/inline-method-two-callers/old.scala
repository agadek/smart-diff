class Invoice:
  def render(name: String, count: Int): String =
    heading(name, count) + "!"

  def summary(name: String, count: Int): String =
    heading(name, count) + "?"

  private def heading(name: String, count: Int): String =
    val text = new StringBuilder("Invoice: ")
    text.append(name).append(count)
    text.append(" lines")
    text.toString()
