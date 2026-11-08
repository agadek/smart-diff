package io.github.agadek.smartdiff.references

/** The per-language resolvers, in the order they are tried (relationships/04 R14). */
internal object ReferenceResolvers {
    val all: List<ReferenceResolver> = listOf(JavaReferenceResolver)
}
