package demo

import java.lang.reflect.Type

class Registry {
    fun adapter(type: Type, annotation: Class<out Annotation>): String = lookup(type, setOf(annotation))

    fun adapter(type: Type, vararg annotations: Class<out Annotation>): String = lookup(type, annotations.toSet())

    private fun lookup(type: Type, annotations: Set<Class<out Annotation>>): String = "$type: $annotations"
}
