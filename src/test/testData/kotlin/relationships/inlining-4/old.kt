class Receipt {
    fun total(customer: String, amount: Int): String {
        return line(customer, amount) + "\n"
    }

    private fun line(customer: String, amount: Int): String {
        val text = StringBuilder(customer)
        text.append(": ").append(amount)
        text.append(" due")
        return text.toString()
    }
}
