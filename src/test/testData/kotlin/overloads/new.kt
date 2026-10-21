package shop

import kotlin.math.max

/** A shopping cart. */
class Cart(private val owner: String) {
    private val items = mutableListOf<Item>()

    fun add(item: Item) {
        items += item
    }

    fun add(name: String, price: Int) = add(Item(name.trim(), price))

    fun add(item: Item, quantity: Int) {
        repeat(quantity) { add(item) }
    }

    fun total(): Int {
        var sum = 0
        for (item in items) {
            sum += item.price
        }
        return sum
    }

    fun describe(): String = "Cart of $owner with ${items.size} items"

    companion object {
        const val LIMIT = 100
    }
}

data class Item(val name: String, val price: Int)

class Receipt {
    fun header(): String = "Receipt"
}

fun Cart.isFull(): Boolean = max(0, total()) > Cart.LIMIT
