package demo

class Order:
  def wrapped(a: Int, b: Int): Int = calc(a, b)

  def chain: Int = calc(1, 2).inc.plus(3)

  def real: Int = calc(1, 2) + 3

object Order:
  def calc(a: Int, b: Int): Int = a + b
