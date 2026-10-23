package shop

class Report(orders: Seq[Order]):

  def render(title: String): String =
    val header = s"== $title =="
    val lines = orders.filter(_.paid).map: order =>
      s"${order.id}: ${order.customer} ${order.items.map(_.price).sum}"
    (header +: lines).mkString("\n")

  def count: Int = orders.size
