package json

/** Writes values as JSON, after Moshi's JsonAdapter. */
abstract class Adapter[T]:
  def toJson(writer: Writer, value: Option[T]): Unit

  def toJson(value: Option[T]): String =
    val buffer = StringBuilder()
    toJson(Writer(buffer), value)
    buffer.toString

  def toJsonValue(value: Option[T]): Any =
    val writer = ValueWriter()
    toJson(writer, value)
    writer.root()

open class Writer(val out: StringBuilder = StringBuilder())

class ValueWriter extends Writer():
  def root(): Any = if out.isEmpty then null else out.toString
