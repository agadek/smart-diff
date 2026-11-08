package io.github.agadek.smartdiff.references

/** The per-language resolvers, in the order they are registered (relationships/04 R14). */
internal object ReferenceResolvers {
    val all: List<ReferenceResolver> get() = ReferenceResolver.EP_NAME.extensionList
}
