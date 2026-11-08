class Receipt {
  def total(customer: String, amount: Int): String = {
    val text =
      new StringBuilder(customer)
    text.append(": ")
      .append(amount)
    text.append(" due")
    text.toString() + "\n"
  }
}
