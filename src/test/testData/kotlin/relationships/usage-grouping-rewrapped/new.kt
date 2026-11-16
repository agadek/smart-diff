class Order {
    fun sum(a: Int, b: Int): Int = a + b
    fun first(): Int = sum(
        1,
        2
    )
    fun second(): Int = sum(3, 4)
}
