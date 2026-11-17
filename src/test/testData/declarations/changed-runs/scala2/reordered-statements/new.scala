class Cart {
  def reset(): Unit = {
    log("reset")
    items.clear()
    total = 0
  }
}
