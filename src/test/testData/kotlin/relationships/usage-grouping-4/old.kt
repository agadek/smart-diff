class Order {
    fun total(): Int { return 1 }
    fun use(other: Order): Int {
        val a = this.total()
        val b = other.total() + 1
        return a + b
    }
}
