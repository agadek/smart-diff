---
status: proposed
---

# A structure-view provider for languages without their own

Dedicated providers give the best outline, but each needs a spec, fixtures and, for paid or closed language plugins, another build target. The [format coverage plan](../FORMAT-COVERAGE.md#3-generic-fallback-group-d) aims to cover every typical language of the JetBrains platform. Almost every language plugin fills the IDE's structure view, through platform API, so a provider built on it reaches every one of them at once.

## Decision

- The declaration engine gets a generic provider that builds the declaration tree from the language's `TreeBasedStructureViewBuilder` on the PSI file from text, keeping only items backed by an element of that file.
- It is used only when no dedicated provider supports the language, and only while the setting for it is on.
- Its declarations have no [signature](../GLOSSARY.md#signature), a kind guessed from the PSI class name or the new generic kind `ITEM`, and the `SIGNIFICANT` whitespace policy, so it never claims [formatting-only](../GLOSSARY.md#formatting-only).
- The outline says when a file was analyzed through the structure view.
- Dedicated providers are built in parallel (decided 2026-11-13) and replace it for their language.

## Considered Options

- **Dedicated providers only:** full quality per language, but most languages stay unsupported for a long time, and every paid IDE needs its own build target first.
- **Generic PSI walk over named elements (`PsiNameIdentifierOwner`):** works without a structure view, but includes local variables, parameters and lambdas, so the outline would be noisy and its nesting wrong.
- **Language Server Protocol document symbols:** a standard tree, but needs a running language server per language and a project, which the text-built diff sides do not have.
- **Tree-sitter:** IDE-independent, but needs native packaging and grammar licence review ([roadmap](../ROADMAP.md#tree-sitter-support)); it stays the route for languages the IDE cannot parse.
- **Structure view (chosen):** platform API, no compile-time dependency on any language plugin, and the IDE's own choice of what is worth listing.

## Consequences

- The plugin builds and tests the provider on the current target with bundled languages; other languages are checked by manual runs and the verifier, not by golden fixtures, until they get a dedicated provider.
- A structure view that needs an editor or a project model, or that fails, makes the file `FAILED` and shows the native diff ([product scope](../specs/README.md#product-scope) P5).
- Structure views change with language plugin releases, so the outline for a fallback language can change without a SMART Diff release. The summary-line note tells the reviewer that this outline is coarser.
- [ADR 0002](0002-parser-neutral-model-with-psi-first.md) holds: the structure view is read through PSI, and the model stays parser-neutral.
