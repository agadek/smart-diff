class Receipt {
    fun total(customer: String, amount: Int): String {
        val text =
            StringBuilder(customer)
        text.append(": ")
            .append(amount)
        text.append(" due")
        return text.toString() + "\n"
    }
}
