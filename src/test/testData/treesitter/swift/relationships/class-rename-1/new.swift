struct Coordinate {
    var x: Double
    var y: Double

    func scaled(by factor: Double) -> Coordinate {
        return Self(x: x * factor, y: y * factor)
    }
}
