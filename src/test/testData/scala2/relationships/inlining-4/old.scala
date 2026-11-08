class Receipt {
  def total(customer: String, amount: Int): String = {
    line(customer, amount) + "\n"
  }

  private def line(customer: String, amount: Int): String = {
    val text = new StringBuilder(customer)
    text.append(": ").append(amount)
    text.append(" due")
    text.toString()
  }
}
