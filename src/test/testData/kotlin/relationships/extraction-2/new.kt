class Invoice {
    fun render(lines: List<String>): String {
        return renderLines(lines)
    }

    private fun renderLines(lines: List<String>): String {
        val out = StringBuilder()
        for (line in lines) {
            out.append(line).append('\n')
        }
        return out.toString()
    }
}
