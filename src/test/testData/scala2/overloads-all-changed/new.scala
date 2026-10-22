package json

/** Writes values as JSON, after Moshi's JsonAdapter. */
abstract class Adapter[T] {
  def toJson(writer: Writer, value: T): Unit

  def toJson(value: T): String = {
    val buffer = new StringBuilder()
    toJson(new Writer(buffer), value)
    buffer.toString
  }

  def toJsonValue(value: T): Any = {
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
