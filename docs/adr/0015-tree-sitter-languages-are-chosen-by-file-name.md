---
status: proposed
---

# Tree-sitter languages are chosen by file name

Engines are selected by the IntelliJ language id of a file ([ADR 0004](0004-pluggable-comparison-engines.md)), and the request's file name is a hint that engines must not depend on. The [Tree-sitter engine](../specs/engines/05-tree-sitter.md) is for files the IDE has no language support for, such as Swift, C#, C and C++ in IntelliJ IDEA. Those files have no language id of their own: they are plain text, a TextMate file, or a file type without a language, so their id cannot tell Swift from C++, and an engine that read the file name itself would break the engine contract.

## Decision

- One resolver, `EngineLanguage.of(languageId, fileName, enabled)` in `engine.registry`, turns such a file into a Tree-sitter language id, such as `tree-sitter.swift`, by its file name's extension. Every caller that selects an engine uses it before `EngineRegistry.select`, and the request carries the resolved id.
- It applies only when the IntelliJ id is plain text, the TextMate language or missing. A file with any other language id keeps it, so language support in the IDE, through PSI or the structure view, always wins ([ADR 0002](0002-parser-neutral-model-with-psi-first.md)).
- Tree-sitter language ids start with `tree-sitter.` and are never IntelliJ language ids, so no PSI provider claims them and stored engine defaults cannot collide with a real language.
- The extension table is part of the Tree-sitter engine's spec; each new language adds its extensions there.

## Considered Options

- **Let the engine read the file name:** no new resolver, but it breaks the rule that engines must not depend on the hint, and every caller that checks whether an engine supports a file would still see plain text.
- **Register file types or languages for Swift, C# and C++ in the plugin:** gives real language ids, but takes over file types that the IDE, the TextMate bundles or another plugin owns, and changes highlighting and other behaviour outside the diff.
- **A resolver by file name in front of engine selection (chosen):** the engines keep reading only the language id, and the rest of the IDE is unchanged.

## Consequences

- The outline, the Changes tree, the merge outline and the line comparison all treat a resolved file as a supported language, with no change to their own rules.
- A file with a non-standard extension, or a shebang-only script, is not resolved; the user can associate the extension with a file type only if a language for it exists.
- Spike S12 confirms which language ids IntelliJ IDEA gives these files; if it finds another id, this ADR lists it before it is accepted.
