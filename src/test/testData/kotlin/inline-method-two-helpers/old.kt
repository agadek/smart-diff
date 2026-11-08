class Basket {
    fun render(name: String, count: Int): String {
        val text = StringBuilder()
        heading(name, count, text)
        footer(name, count, text)
        return text.toString()
    }

    private fun heading(name: String, count: Int, text: StringBuilder) {
        text.append("Basket: ")
        text.append("owner ").append(name)
        text.append(" qty ").append(count)
    }

    private fun footer(name: String, count: Int, text: StringBuilder) {
        val end = StringBuilder(" / ")
        end.append("subtotal: ").append(name)
        end.append(" x").append(count).append(" units")
        text.append(end)
    }
}
