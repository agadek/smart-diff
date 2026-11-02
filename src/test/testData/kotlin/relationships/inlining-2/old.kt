class Invoice {
    fun render(name: String): String {
        return heading(name) + "!"
    }

    private fun heading(name: String): String {
        val h = "Report: " + name
        return h
    }
}
