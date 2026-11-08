class Note {
    fun title(name: String): String {
        return prefix(name) + "!"
    }

    private fun prefix(name: String): String {
        return "Note: " + name
    }
}
