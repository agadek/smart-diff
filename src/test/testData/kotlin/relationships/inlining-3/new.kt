class Basket {
    fun render(name: String, count: Int): String {
        val text = StringBuilder()
        text.append("Basket: ")
        text.append("owner ").append(name)
        text.append(" qty ").append(count)
        val end = StringBuilder(" / ")
        end.append("subtotal: ").append(name)
        end.append(" x").append(count).append(" units")
        text.append(end)
        return text.toString()
    }
}
