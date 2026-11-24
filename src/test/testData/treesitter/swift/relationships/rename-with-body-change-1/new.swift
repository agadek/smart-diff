func formatAmount(cents: Int) -> String {
    let dollars = cents / 100
    let rest = cents % 100
    let padded = rest < 10 ? "0\(rest)" : "\(rest)"
    return "USD \(dollars).\(padded)"
}

func formatCount(count: Int) -> String {
    return "\(count) items"
}
