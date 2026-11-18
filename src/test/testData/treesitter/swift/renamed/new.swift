final class Cart {
    func total(prices: [Int]) -> Int {
        var sum = 0
        for price in prices { sum += price }
        return sum
    }
}
