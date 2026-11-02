class Invoice {
  def render(name: String): String = {
    heading(name) + "!"
  }

  private def heading(name: String): String = {
    val h = "Report: " + name
    h
  }
}
