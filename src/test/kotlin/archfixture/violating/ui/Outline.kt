package archfixture.violating.ui

import archfixture.violating.engine.declarations.DeclarationEngine

// Violates: ui depends on a concrete engine.
class Outline(val engine: DeclarationEngine)
