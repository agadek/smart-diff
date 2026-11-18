import Foundation

/// A priced thing.
protocol Priced {
    var price: Double { get }
    func cost(quantity: Int) -> Double
}

/// The cart.
@MainActor
final class Cart<Item>: Priced {
    let a = 1, b = 2
    var price: Double { 0 }

    init?(items: [Item] = []) {}

    subscript(index: Int) -> Item? { nil }

    func cost(quantity: Int) -> Double { 0 }

    deinit {}
}

extension Cart where Item: Hashable {
    func unique() -> Int { 0 }
}

actor Counter {}

typealias Basket = Cart<String>
