package json

/** Writes values as JSON, after Moshi's JsonAdapter. */
abstract class Adapter<T> {
    abstract fun toJson(writer: Writer, value: T?)

    fun toJson(value: T?): String {
        val buffer = StringBuilder()
        toJson(Writer(buffer), value)
        return buffer.toString()
    }

    fun toJsonValue(value: T?): Any? {
        val writer = ValueWriter()
        toJson(writer, value)
        return writer.root()
    }
}

open class Writer(val out: StringBuilder = StringBuilder())

class ValueWriter : Writer() {
    fun root(): Any? = out.toString().ifEmpty { null }
}
