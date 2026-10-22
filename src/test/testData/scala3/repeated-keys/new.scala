package demo

import java.lang.annotation.Annotation
import java.lang.reflect.Type

class Registry:
  def adapter(tpe: Type, annotation: Class[? <: Annotation]): String = lookup(tpe, Set(annotation))

  def adapter(tpe: Type, annotations: Class[? <: Annotation]*): String = lookup(tpe, annotations.toSet)

  private def lookup(tpe: Type, annotations: Set[Class[? <: Annotation]]): String = s"$tpe: $annotations"
