import Foundation

/// A priced thing, with a price.
protocol Priced {
    var price: Double { get }
    func cost(quantity: Int) -> Double
}

/// The cart.
@MainActor
final class Cart<Item>: Priced {
    let a = 1, b = 3
    var price: Double { 0 }

    init?(items: [Item] = []) {}

    subscript(index: Int) -> Item? { nil }

    func cost(quantity: Int) -> Double { 1 }

    deinit {}
}

extension Cart where Item: Hashable {
    func unique() -> Int { 0 }
}

actor Counter {}

typealias Basket = Cart<Int>
