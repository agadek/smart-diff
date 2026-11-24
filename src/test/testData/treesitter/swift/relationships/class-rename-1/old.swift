struct Point {
    var x: Double
    var y: Double

    func scaled(by factor: Double) -> Point {
        return Self(x: x * factor, y: y * factor)
    }
}
