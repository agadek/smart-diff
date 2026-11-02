class Invoice:
  def render(lines: List[String]): String =
    val out = new StringBuilder()
    for line <- lines do
      out.append(line).append('\n')
    out.toString()
