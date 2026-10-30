---
status: accepted
---

# Comparison engines are pluggable behind a text-in, result-out interface

Every way of comparing the old side and new side is a comparison engine behind one interface. The interface takes the two texts and a language id and returns an engine-neutral result. The UI, navigation and settings depend only on that interface, never on a specific engine. Engines are registered through an extension point and identified by a stable id, so a new engine can be added, or the default swapped, without touching the UI. When more than one engine supports a language, the user picks one from a dropdown in the structural outline, and the choice is remembered per language. We expect several engines over time (declaration matching first; later fine-grained Tree-sitter, Difftastic or GumTree based ones), and their quality is to be compared on real reviews rather than decided up front.

## Considered Options

- **Boundary after parsing** (engines receive declaration trees built by shared providers): less duplicated parsing, but it forces every future engine onto IntelliJ PSI and our declaration model, which rules out Difftastic, GumTree or any engine with its own parser.
- **Boundary at text (chosen):** each engine owns its parsing; the shared declaration model in [ADR 0002](0002-parser-neutral-model-with-psi-first.md) becomes the internal model of the first engine.
- **No abstraction until a second engine exists:** less code now, but the UI would grow around one engine's types and be costly to untangle later.

## Consequences

- The result model must describe changes that are not declarations (regions, fine-grained spans), even though the first engine only produces declarations and file regions.
- All engines must pass a shared contract test suite, including the [ADR 0003](0003-conservative-classification.md) rule that nothing is hidden: every differing character is covered by a reported change or marked unanalyzed.
- An engine runs off the UI thread and manages its own read actions, so an engine that calls an external process does not hold IntelliJ read locks.
- The dropdown is hidden while only one engine supports the current language, as in the first release.
- The extension point is internal for now: only SMART Diff and its own add-ons register engines, so the interface and result model can still change freely. Opening it to third-party plugins would make them a public, versioned contract and expose source code to engines we cannot test; revisit once a second production engine has used the result model without breaking changes.

## Review comments

> **Model author:** DeepSeek V4.1 Flash · 2026-10-30. These are review notes; the normative text above is unchanged.

- Add that multi-file or reference-aware requests would require an API extension; the current text-in/result-out contract remains the baseline.
