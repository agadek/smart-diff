class Invoice {
    fun first(x: Int): Int {
        return doubled(x) + 1
    }
    fun second(x: Int): Int {
        return doubled(x) * 3
    }
    fun doubled(x: Int): Int {
        val doubled = x * 2
        val result = doubled + 1
        return result
    }
}
