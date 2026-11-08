class Order {
    fun render(name: String, count: Int): String {
        val text = StringBuilder("Report: ")
        text.append(name).append(count)
        text.append(" items")
        return text.toString() + "!"
    }
}
