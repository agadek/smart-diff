class Cart {
  def reset(): Unit = {
    items.clear()
    total = 0
    log("reset")
  }
}
