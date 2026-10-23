package shop

class Cart(private val items: MutableList<Item>) {

    fun checkout(customer: Customer): Receipt {
        require(items.isNotEmpty()) { "empty cart" }
        var total = 0L
        for (item in items) {
            val discount = if (customer.isMember) item.price * 5 / 100 else 0
            total += (item.price - discount) * item.quantity
        }
        val receipt = Receipt(customer.name, total)
        items.clear()
        return receipt
    }

    fun describe(): String {
        val lines = items.map { item -> "${item.name} x${item.quantity}: ${item.price * item.quantity}" }
        return lines.joinToString(separator = "\n", prefix = "Cart:\n")
    }

    fun heaviest(): Item? {
        var best: Item? = null
        for (item in items) {
            if (best == null || item.weight * item.quantity > best.weight * best.quantity) best = item
        }
        return best
    }
}
