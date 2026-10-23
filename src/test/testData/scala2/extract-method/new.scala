package shop

class Report(orders: Seq[Order]) {

  def render(title: String): String = {
    val header = s"== $title =="
    (header +: paidLines).mkString("\n")
  }

  private def paidLines: Seq[String] = {
    orders.filter(_.paid).map { order =>
      s"${order.id}: ${order.customer} ${order.items.map(_.price).sum}"
    }
  }

  def count: Int = orders.size
}
