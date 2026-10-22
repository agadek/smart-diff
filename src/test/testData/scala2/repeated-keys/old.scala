package demo

import java.lang.annotation.Annotation
import java.lang.reflect.Type

class Registry {
  def adapter(tpe: Type, annotation: Class[_ <: Annotation]): String = lookup(tpe, Set(annotation))

  def adapter(tpe: Type, annotations: Class[_ <: Annotation]*): String = lookup(tpe, annotations.toSet)

  private def lookup(tpe: Type, annotations: Set[Class[_ <: Annotation]]): String = s"$tpe $annotations"

  /** `enum` is a name in Scala 2 only. */
  private val enum = 0
}
