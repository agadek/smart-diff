package shop

import scala.collection.mutable.ListBuffer
import scala.math.max

/** A shopping cart. */
class Cart(owner: String) {
  private val items = ListBuffer.empty[Item]

  def add(item: Item): Unit = {
    items += item
  }

  def add(name: String, price: Int): Unit = add(Item(name, price))

  def total(): Int = {
    var sum = 0
    for (item <- items) {
      sum += item.price
    }
    sum
  }

  def describe(): String = s"Cart of $owner with ${items.size} items"
}

object Cart {
  val Limit = 100
}

class Receipt {
  def header(): String = "Receipt"
}

/** `enum` is a name in Scala 2 only. */
case class Item(name: String, price: Int, enum: Int = 0)

object CartSyntax {
  implicit class CartOps(cart: Cart) {
    def isFull: Boolean = max(0, cart.total()) > Cart.Limit
  }
}
