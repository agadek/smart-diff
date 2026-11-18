final class Cart {
    func total(prices: [Int]) -> Int {
        return prices.reduce(0, +)
    }

    func count() -> Int { 0 }
}
