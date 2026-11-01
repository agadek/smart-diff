object Plain {
  def f() = 1
}

given Ordering[Int] = Ordering.Int
enum Color { case Red, Green }
extension (s: String) def shout: String = s.toUpperCase
