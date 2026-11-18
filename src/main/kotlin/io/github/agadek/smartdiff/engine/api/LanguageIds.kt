package io.github.agadek.smartdiff.engine.api

/** Stable language identifiers used by providers and engines. */
object LanguageIds {
    const val PYTHON = "Python"

    /** The prefix of every Tree-sitter language id; none is an IntelliJ language id (ADR 0015). */
    const val TREE_SITTER_PREFIX = "tree-sitter."

    // engines/05 R2, R3: the Tree-sitter languages, chosen by file name (ADR 0015).
    const val TREE_SITTER_SWIFT = "tree-sitter.swift"
    const val TREE_SITTER_C_SHARP = "tree-sitter.c-sharp"
    const val TREE_SITTER_C = "tree-sitter.c"
    const val TREE_SITTER_CPP = "tree-sitter.cpp"
}
