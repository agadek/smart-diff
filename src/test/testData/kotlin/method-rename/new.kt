package shop

/** A shopping cart. */
class Cart(private val owner: String) {
    private val entries = mutableListOf<Item>()

    fun put(item: Item) {
        entries += item
        println("added ${item.name}")
    }

    fun total(): Int {
        var sum = 0
        for (item in entries) {
            sum += item.price
        }
        return sum
    }

    fun summary(): String = "Basket of $owner with ${entries.size} items"

    fun clear() {}
}

data class Item(val name: String, val price: Int)

fun Cart.hasNothing(): Boolean = total() == 0 && summary().isNotEmpty()
