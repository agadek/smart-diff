package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.model.Declaration

/** The engine-neutral [Subject] of [declaration], as the outline and the relationships name it. */
internal fun declarationSubject(declaration: Declaration): Subject =
    Subject.Declaration(declaration.kind, declaration.name, declaration.displaySignature, declaration.receiver)
