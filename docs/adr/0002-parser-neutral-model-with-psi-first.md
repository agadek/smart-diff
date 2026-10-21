---
status: accepted
---

# Parser-neutral declaration model, with IntelliJ PSI as the first provider

Matching and classification run on a plain-Kotlin declaration model with no IntelliJ Platform types, filled in by per-language parser providers. The first providers use IntelliJ PSI. PSI parses in-memory text, needs no bundled native code or grammar licences, and its offsets map straight to the diff editors. The neutral model keeps the matcher testable without an IDE and keeps the product from becoming tied to any single language or parser.

## Considered Options

- **Tree-sitter first:** broad, IDE-independent language coverage, but needs a native JVM binding per OS, grammar packaging and per-grammar licence review before the first useful result.
- **Difftastic as the engine:** brings many languages and a mature matcher, but its JSON output is unstable and has no declaration information, so a parser would still be needed on top.
- **PSI as the shared model:** least code, but ties the matcher to IntelliJ types and to one language's tree shape.

## Consequences

- Since [ADR 0004](0004-pluggable-comparison-engines.md), this model and its providers are internal to the declaration engine; other comparison engines may use different parsers and models.
- Language coverage depends on the host IDE and its installed plugins. A language the IDE cannot parse is shown as unsupported and opens in the native diff.
- A Tree-sitter provider can be added later behind the same interface for languages PSI does not cover.
- PSI files built from text have no project classpath, so providers must not rely on resolving references or types; signatures use the parameter types as written.
