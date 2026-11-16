package demo

class Order {
    fun wrapped(a: Int, b: Int): Int = calc(a, b)

    fun chain(): Int = calc(1, 2).inc().plus(3)

    fun real(): Int = calc(1, 2) + 3
}

fun calc(a: Int, b: Int): Int = a + b
