class Basket {
    fun compute(x: Int): Int {
        return doubled(x) + trimmed(x)
    }
    fun doubled(x: Int): Int {
        val doubled = x * 2
        val plus = doubled + 1
        return plus * 2
    }
    fun trimmed(x: Int): Int {
        val tripled = x * 3
        val minus = tripled - 1
        return minus / 2
    }
}
