class Cart:
  def clear(): Unit =
    if items.nonEmpty then
      items.clear()
    total = 0
