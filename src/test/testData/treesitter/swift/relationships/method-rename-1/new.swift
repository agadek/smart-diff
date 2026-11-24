final class Stats {
    func sum(values: [Int]) -> Int {
        var sum = 0
        for value in values { sum += value }
        return sum
    }

    func largest(values: [Int]) -> Int {
        var best = Int.min
        for value in values where value > best { best = value }
        return best
    }
}
