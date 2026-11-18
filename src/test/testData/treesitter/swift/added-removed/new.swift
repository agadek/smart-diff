struct Point {
    var x: Int
    var z: Int

    func length() -> Double { 0 }

    func scaled(by factor: Double) -> Point { self }
}
