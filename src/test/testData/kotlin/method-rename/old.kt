package shop

/** A shopping cart. */
class Cart(private val owner: String) {
    private val entries = mutableListOf<Item>()

    fun put(item: Item) {
        entries += item
        println("added ${item.name}")
    }

    fun computeTotal(): Int {
        var sum = 0
        for (item in entries) {
            sum += item.price
        }
        return sum
    }

    fun describe(): String = "Cart of $owner with ${entries.size} items"

    fun reset() {}
}

data class Item(val name: String, val price: Int)

fun Cart.isEmptyCart(): Boolean = computeTotal() == 0 && describe().isNotEmpty()
