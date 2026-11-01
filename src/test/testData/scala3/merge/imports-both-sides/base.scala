package merge

import scala.collection.mutable.ListBuffer
import scala.collection.mutable.Map

class OrderService:
  val names: ListBuffer[String] = ListBuffer.empty
  val counts: Map[String, Int] = Map.empty
