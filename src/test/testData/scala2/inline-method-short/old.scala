class Note {
  def title(name: String): String = {
    prefix(name) + "!"
  }

  private def prefix(name: String): String = {
    "Note: " + name
  }
}
