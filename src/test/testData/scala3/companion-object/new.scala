package shop

import scala.collection.mutable.ListBuffer
import scala.math.max

/** A shopping cart. */
class Cart(owner: String):
  private val items = ListBuffer.empty[Item]

  def add(item: Item): Unit =
    items += item

  def add(name: String, price: Int): Unit = add(Item(name, price))

  def total(): Int =
    var sum = 0
    for item <- items do
      sum += item.price
    sum

  def describe(): String = s"Cart of $owner with ${items.size} items"

object Cart:
  val Limit = 200

  def empty(owner: String): Cart = Cart(owner)

case class Item(name: String, price: Int)

class Receipt:
  def header(): String = "Receipt"

extension (cart: Cart)
  def isFull: Boolean = max(0, cart.total()) > Cart.Limit
