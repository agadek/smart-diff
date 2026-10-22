package json

/** Writes values as JSON, after Moshi's JsonAdapter. */
abstract class Adapter[T] {
  def toJson(writer: Writer, value: Option[T]): Unit

  def toJson(value: Option[T]): String = {
    val buffer = new StringBuilder()
    toJson(new Writer(buffer), value)
    buffer.toString
  }

  def toJsonValue(value: Option[T]): Any = {
    val writer = new ValueWriter()
    toJson(writer, value)
    writer.root()
  }
}

class Writer(val out: StringBuilder = new StringBuilder())

/** `enum` is a name in Scala 2 only. */
class ValueWriter(enum: Int = 0) extends Writer() {
  def root(): Any = if (out.isEmpty) null else out.toString
}
