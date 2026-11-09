package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.RelationshipKind

/** Which tree the structural outline shows (relationships/06 R1, R2). */
enum class OutlineView {
    /** The container tree: declarations grouped by container and change type, the default. */
    CONTAINER_TREE,

    /** Relationships first, then the changes no relationship covers. */
    REFACTORINGS,
}

/** The menu and settings label of a [RelationshipKind] (relationships/06 R14). */
fun relationshipKindText(kind: RelationshipKind): String = when (kind) {
    RelationshipKind.RENAME -> "Rename"
    RelationshipKind.MOVE -> "Move"
    RelationshipKind.KIND_CHANGE -> "Kind change"
    RelationshipKind.EXTRACT -> "Extraction"
    RelationshipKind.INLINE -> "Inlining"
}
