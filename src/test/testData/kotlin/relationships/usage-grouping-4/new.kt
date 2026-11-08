class Order {
    fun sum(): Int { return 1 }
    fun use(other: Order): Int {
        val a = this.sum()
        val b = this.sum() + 1
        return a + b
    }
}
