package archfixture.violating.engine.declarations

import archfixture.violating.engine.lines.LineEngine

// Violates: one engine depends on another.
class DeclarationEngine(val fallback: LineEngine)
