package shop

class Cart(private val items: MutableList<Item>) {

    fun checkout(customer: Customer): Receipt {
        require(items.isNotEmpty()) { "empty cart" }
        val receipt = Receipt(customer.name, totalFor(customer))
        items.clear()
        return receipt
    }

    private fun totalFor(customer: Customer): Long {
        var total = 0L
        for (item in items) {
            val discount = if (customer.isMember) item.price * 5 / 100 else 0
            total += (item.price - discount) * item.quantity
        }
        return total
    }

    fun describe(): String = items.describeLines()

    fun heaviest(): Item? = Weights.heaviest(items)
}

private fun List<Item>.describeLines(): String =
    map { item -> "${item.name} x${item.quantity}: ${item.price * item.quantity}" }
        .joinToString(separator = "\n", prefix = "Cart:\n")

object Weights {
    fun heaviest(items: List<Item>): Item? {
        var best: Item? = null
        for (item in items) {
            if (best == null || item.totalWeight > best.totalWeight) best = item
        }
        return best
    }
}

val Item.totalWeight: Int get() = weight * quantity
