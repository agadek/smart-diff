package io.github.agadek.smartdiff.engine.declarations.psi.scala

import scala.Option
import scala.collection.immutable.Seq
import scala.jdk.javaapi.CollectionConverters

/** Calls into the Scala plugin's PSI that need Scala library types; the only place the provider touches them. */
internal object ScalaInterop {

    fun <T> list(seq: Seq<T>): List<T> = CollectionConverters.asJava(seq)

    fun <T> orNull(option: Option<T>): T? = if (option.isDefined) option.get() else null
}
