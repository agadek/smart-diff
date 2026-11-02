class Basket {
    fun render(lines: List<String>): String {
        val out = StringBuilder()
        for (line in lines) {
            out.append(line).append('\n')
        }
        return out.toString()
    }
}
