class Order {
    fun total(): Int { return 1 }
    fun total(value: Int): Int { return value }
    fun use(): Int {
        val a = total()
        val b = total(2)
        val c = unknown()
        return a + b + c
    }
}
