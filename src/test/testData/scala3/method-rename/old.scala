package shop

import scala.collection.mutable.ListBuffer

/** A shopping cart. */
class Cart(owner: String):
  private val entries = ListBuffer.empty[Item]

  def put(item: Item): Unit =
    entries += item
    println(s"added ${item.name}")

  def computeTotal(): Int =
    var sum = 0
    for item <- entries do
      sum += item.price
    sum

  def describe(): String = s"Cart of $owner with ${entries.size} items"

  def reset(): Unit = ()

case class Item(name: String, price: Int)

extension (cart: Cart)
  def isEmptyCart: Boolean = cart.computeTotal() == 0 && cart.describe().nonEmpty
