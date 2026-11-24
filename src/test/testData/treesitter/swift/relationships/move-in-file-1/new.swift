func third(values: [Int]) -> Int {
    return values.dropFirst(2).first ?? 0
}

func first(values: [Int]) -> Int {
    return values.first ?? 0
}

func second(values: [Int]) -> Int {
    return values.dropFirst().first ?? 0
}
