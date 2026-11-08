class Invoice {
    fun render(name: String, count: Int): String {
        return heading(name, count) + "!"
    }

    fun summary(name: String, count: Int): String {
        return heading(name, count) + "?"
    }

    private fun heading(name: String, count: Int): String {
        val text = StringBuilder("Invoice: ")
        text.append(name).append(count)
        text.append(" lines")
        return text.toString()
    }
}
