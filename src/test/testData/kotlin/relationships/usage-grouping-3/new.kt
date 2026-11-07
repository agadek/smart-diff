class Order {
    fun sum(): Int { return 1 }
    fun total(value: Int): Int { return value }
    fun use(): Int {
        val a = sum()
        val b = total(2)
        val c = unknown()
        return a + b + c
    }
}
