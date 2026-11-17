class Cart {
    fun total(limit: Int): Int {
        var sum = 0
        for (item in items) {
            if (item.price >= limit) sum += item.price
        }
        return sum
    }
}
