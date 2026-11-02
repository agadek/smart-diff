class Invoice {
  def render(lines: List[String]): String = {
    val out = new StringBuilder()
    for (line <- lines) {
      out.append(line).append('\n')
    }
    out.toString()
  }
}
