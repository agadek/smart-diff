package json

/** Writes values as JSON, after Moshi's JsonAdapter. */
abstract class Adapter[T]:
  def toJson(writer: Writer, value: T): Unit

  def toJson(value: T): String =
    val buffer = StringBuilder()
    toJson(Writer(buffer), value)
    buffer.toString

  def toJsonValue(value: T): Any =
    val writer = ValueWriter()
    toJson(writer, value)
    writer.root()

open class Writer(val out: StringBuilder = StringBuilder())

class ValueWriter extends Writer():
  def root(): Any = if out.isEmpty then null else out.toString
